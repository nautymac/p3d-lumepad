package com.nauty.p3d.net;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.IOException;
import java.util.EnumSet;

/**
 * {@code smb://} 를 여는 ExoPlayer {@link androidx.media3.datasource.DataSource}.
 * smbj (SMB2/3) 로 서버에 붙는다 (HANDOFF 1-② 참고).
 *
 * seek 는 {@code skip()} 을 쓰지 않는다 — smbj 의 {@link File#read(byte[], long, int, int)}
 * 가 임의 위치를 직접 읽을 수 있어서, 매 read() 마다 지금 위치를 넘기기만 하면 된다.
 *
 * <p>작게 잘라 읽으면 안 된다. MKV(EBML) 헤더 파싱은 1~4바이트짜리 read() 를 수천 번
 * 부른다 — 각각이 SMB2 왕복 하나라 왕복 하나가 5ms 만 걸려도 다 더하면 실기에서
 * 8.5GB 4K 파일 하나 여는 데 1분을 넘겼다(실측). 네트워크가 아니라 왕복 횟수가
 * 문제였다. {@link #localBuf} 로 한 번에 크게 채워 두고, 그 범위 안의 작은 read() 는
 * 네트워크 없이 메모리에서 바로 돌려준다. 이미 큰 요청(스트리밍 본편 데이터)은
 * 캐시를 거치지 않고 그대로 직접 읽는다 — 어차피 한 번에 충분히 크다.
 */
public final class SmbDataSource extends BaseDataSource {

    private static final String TAG = "P3D";

    /** 이보다 작은 요청만 캐시를 채운다/에서 돌려준다. 이보다 크면 바로 읽는 편이 낫다. */
    private static final int BUFFER_SIZE = 256 * 1024;

    /** 자격증명을 URI 에서 못 찾을 때 {@link SmbCredentials} 를 찾아볼 대상. null 가능. */
    private final Context ctx;

    private SMBClient client;
    private DiskShare share;
    private File file;

    private Uri uri;
    private long position;
    private long bytesRemaining;

    private byte[] localBuf;
    private long localBufStart = -1;
    private int localBufLen = 0;

    public SmbDataSource(Context ctx) {
        super(/* isNetwork= */ true);
        this.ctx = ctx == null ? null : ctx.getApplicationContext();
    }

    @Override
    public long open(DataSpec spec) throws IOException {
        uri = spec.uri;
        transferInitializing(spec);

        SmbUri parsed = SmbUri.parse(spec.uri);
        SmbCredentials.Entry cred = parsed.hasCredentials()
                ? new SmbCredentials.Entry(parsed.user, parsed.pass, null)
                : (ctx == null ? null : SmbCredentials.find(ctx, parsed.host));

        long fileLength;
        long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            client = new SMBClient();
            Connection connection = client.connect(parsed.host, parsed.port);
            AuthenticationContext auth = (cred == null || cred.user == null || cred.user.isEmpty())
                    ? AuthenticationContext.anonymous()
                    : new AuthenticationContext(cred.user,
                            cred.pass == null ? new char[0] : cred.pass.toCharArray(), cred.domain);
            Session session = connection.authenticate(auth);
            share = (DiskShare) session.connectShare(parsed.share);
            file = share.openFile(parsed.path,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null);
            fileLength = file.getFileInformation().getStandardInformation().getEndOfFile();
            Log.i(TAG, "SMB 열기 완료 (" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms): "
                    + parsed.host + "/" + parsed.share + "\\" + parsed.path + " (" + fileLength + " bytes)");
        } catch (IOException e) {
            close();
            throw e;
        } catch (Exception e) {
            close();
            throw new IOException("SMB 열기 실패: " + parsed.host + "/" + parsed.share
                    + "\\" + parsed.path, e);
        }

        position = spec.position;
        localBufStart = -1;
        localBufLen = 0;
        if (spec.length != C.LENGTH_UNSET) {
            bytesRemaining = spec.length;
        } else if (fileLength >= 0) {
            bytesRemaining = fileLength - position;
        } else {
            bytesRemaining = C.LENGTH_UNSET;
        }
        if (bytesRemaining < 0) {
            throw new IOException("요청 범위가 파일 길이를 벗어났습니다: " + parsed.path);
        }

        transferStarted(spec);
        return bytesRemaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;

        int want = bytesRemaining == C.LENGTH_UNSET
                ? length : (int) Math.min(length, bytesRemaining);

        // 캐시 범위 안이면 네트워크를 타지 않는다.
        if (localBufStart >= 0 && position >= localBufStart
                && position < localBufStart + localBufLen) {
            int avail = (int) (localBufStart + localBufLen - position);
            int n = Math.min(want, avail);
            System.arraycopy(localBuf, (int) (position - localBufStart), buffer, offset, n);
            advance(n);
            return n;
        }

        // 큰 요청은 캐시를 거치지 않고 그대로 읽는다 — 이미 한 번에 충분히 크다.
        if (want >= BUFFER_SIZE) {
            int read = rawRead(buffer, position, offset, want);
            if (read <= 0) return endOfInputOrThrow();
            advance(read);
            return read;
        }

        // 캐시 미스 — 한 번에 크게 채워서 앞으로의 작은 read() 들이 이 안에서 끝나게 한다.
        if (localBuf == null) localBuf = new byte[BUFFER_SIZE];
        int filled = rawRead(localBuf, position, 0, BUFFER_SIZE);
        if (filled <= 0) return endOfInputOrThrow();
        localBufStart = position;
        localBufLen = filled;

        int n = Math.min(want, filled);
        System.arraycopy(localBuf, 0, buffer, offset, n);
        advance(n);
        return n;
    }

    private int rawRead(byte[] buffer, long filePos, int offset, int length) throws IOException {
        try {
            return file.read(buffer, filePos, offset, length);
        } catch (Exception e) {
            throw new IOException("SMB 읽기 실패", e);
        }
    }

    private void advance(int n) {
        position += n;
        if (bytesRemaining != C.LENGTH_UNSET) bytesRemaining -= n;
        bytesTransferred(n);
    }

    private int endOfInputOrThrow() throws IOException {
        if (bytesRemaining != C.LENGTH_UNSET && bytesRemaining != 0) {
            throw new IOException("SMB 파일이 예상보다 짧습니다");
        }
        return C.RESULT_END_OF_INPUT;
    }

    @Override public Uri getUri() { return uri; }

    @Override
    public void close() {
        try { if (file != null) file.close(); } catch (Exception ignored) { }
        try { if (share != null) share.close(); } catch (Exception ignored) { }
        try { if (client != null) client.close(); } catch (Exception ignored) { }
        file = null;
        share = null;
        client = null;
        localBufStart = -1;
        localBufLen = 0;
        transferEnded();
    }
}
