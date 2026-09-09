package com.nauty.p3d.net;

import android.content.Context;
import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@code smb://} 는 {@link SmbDataSource} 로, 그 밖의 스킴(http/https/content/file 등)은
 * 기존 {@link DefaultDataSource} 로 보낸다.
 *
 * DLNA 는 결국 평범한 http URL 을 주므로 재생 엔진은 손댈 것이 없다 — SMB 에 이어
 * 얹은 것은 유튜브(YouTube.java)가 요구하는 요청 헤더뿐이다: 그 호스트로 가는
 * http(s) 요청에는 {@link StreamHeaders} 에 등록된 헤더를 실어 보낸다. HLS 는
 * 재생목록·조각이 같은 호스트로 여러 번 요청되므로 헤더도 호스트 단위로 적용된다.
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
            if ("smb".equals(spec.uri.getScheme())) {
                active = new SmbDataSource(ctx);
            } else {
                Map<String, String> headers = StreamHeaders.get(spec.uri.getHost());
                active = headers == null ? httpDelegate
                        : new DefaultHttpDataSource.Factory()
                                .setDefaultRequestProperties(headers)
                                .createDataSource();
            }
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
