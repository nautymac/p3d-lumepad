package com.nauty.p3d.net;

import android.content.Context;
import android.net.Uri;

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
 * 4K 스트리밍이 끊기지 않으려면 ExoPlayer 쪽 읽기 블록 크기가 충분히 커야 한다 —
 * 기본 로더 설정을 그대로 쓰면 대체로 1MB 안팎이라 실측상 문제 없었다.
 */
public final class SmbDataSource extends BaseDataSource {

    /** 자격증명을 URI 에서 못 찾을 때 {@link SmbCredentials} 를 찾아볼 대상. null 가능. */
    private final Context ctx;

    private SMBClient client;
    private DiskShare share;
    private File file;

    private Uri uri;
    private long position;
    private long bytesRemaining;

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
                : (ctx == null ? null : SmbCredentials.find(ctx, parsed.host, parsed.share));

        long fileLength;
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
        } catch (IOException e) {
            close();
            throw e;
        } catch (Exception e) {
            close();
            throw new IOException("SMB 열기 실패: " + parsed.host + "/" + parsed.share
                    + "\\" + parsed.path, e);
        }

        position = spec.position;
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

        int toRead = bytesRemaining == C.LENGTH_UNSET
                ? length : (int) Math.min(length, bytesRemaining);

        int read;
        try {
            read = file.read(buffer, position, offset, toRead);
        } catch (Exception e) {
            throw new IOException("SMB 읽기 실패", e);
        }

        if (read <= 0) {
            if (bytesRemaining != C.LENGTH_UNSET && bytesRemaining != 0) {
                throw new IOException("SMB 파일이 예상보다 짧습니다");
            }
            return C.RESULT_END_OF_INPUT;
        }

        position += read;
        if (bytesRemaining != C.LENGTH_UNSET) bytesRemaining -= read;
        bytesTransferred(read);
        return read;
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
        transferEnded();
    }
}
