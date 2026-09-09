package com.nauty.p3d.net;

import android.content.Context;
import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code smb://} 는 {@link SmbDataSource} 로, 그 밖의 스킴(http/https/content/file 등)은
 * 기존 {@link DefaultDataSource} 로 보낸다.
 *
 * DLNA 는 결국 평범한 http URL 을 주므로 재생 엔진은 손댈 것이 없다 — 여기서 새로
 * 얹은 것은 SMB 뿐이다 (HANDOFF 1-② 참고).
 */
public final class NetDataSourceFactory implements DataSource.Factory {

    private final Context appContext;

    public NetDataSourceFactory(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    @Override
    public DataSource createDataSource() {
        return new Dispatch(appContext);
    }

    private static final class Dispatch implements DataSource {
        private final Context ctx;
        private final DataSource httpDelegate;
        private DataSource active;

        Dispatch(Context ctx) {
            this.ctx = ctx;
            this.httpDelegate = new DefaultDataSource.Factory(ctx).createDataSource();
        }

        @Override public void addTransferListener(TransferListener l) {
            httpDelegate.addTransferListener(l);
        }

        @Override
        public long open(DataSpec spec) throws IOException {
            active = "smb".equals(spec.uri.getScheme()) ? new SmbDataSource(ctx) : httpDelegate;
            return active.open(spec);
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            return active.read(buffer, offset, length);
        }

        @Override public Uri getUri() { return active == null ? null : active.getUri(); }

        @Override public Map<String, List<String>> getResponseHeaders() {
            return active == null ? Collections.<String, List<String>>emptyMap()
                    : active.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            if (active != null) { active.close(); active = null; }
        }
    }
}
