package com.nauty.p3d.net;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * mDNS(Bonjour) 로 SMB 서버를 찾는다.
 *
 * Synology·QNAP·macOS 공유는 기본적으로 {@code _smb._tcp.} 를 광고한다 (macOS
 * Finder 의 "네트워크" 가 이걸로 찾는다). 평범한 Windows 공유 PC 는 광고하지
 * 않는 경우가 많아 여기 안 잡힐 수 있다 — 그때는 IP 를 직접 입력하는 길을 남겨 둔다.
 *
 * SSDP(Ssdp.java) 와 달리 멀티캐스트 소켓을 직접 열지 않는다. Android 의
 * NsdManager 가 mDNS 질의·응답을 대신 처리해 준다.
 */
public final class SmbDiscovery {

    private static final String SERVICE_TYPE = "_smb._tcp.";

    public static final class Host {
        public final String name;
        public final String address;
        public final int port;

        Host(String name, String address, int port) {
            this.name = name;
            this.address = address;
            this.port = port;
        }
    }

    public interface Callback {
        void onFinished(List<Host> hosts);
    }

    private SmbDiscovery() {}

    /** timeoutMs 뒤에 그때까지 찾은 것을 콜백으로 돌려준다 (메인 스레드에서 불린다). */
    public static void discover(Context ctx, final long timeoutMs, final Callback cb) {
        final NsdManager nsd = (NsdManager) ctx.getApplicationContext()
                .getSystemService(Context.NSD_SERVICE);
        final Handler ui = new Handler(Looper.getMainLooper());
        if (nsd == null) {
            cb.onFinished(new ArrayList<Host>());
            return;
        }

        final List<Host> found = new ArrayList<>();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final NsdManager.DiscoveryListener[] listenerHolder = new NsdManager.DiscoveryListener[1];

        final Runnable finish = new Runnable() {
            @Override public void run() {
                if (!finished.compareAndSet(false, true)) return;
                try {
                    if (listenerHolder[0] != null) nsd.stopServiceDiscovery(listenerHolder[0]);
                } catch (Exception ignored) { }
                List<Host> copy;
                synchronized (found) { copy = new ArrayList<>(found); }
                cb.onFinished(copy);
            }
        };

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) { }

            @Override public void onServiceFound(NsdServiceInfo info) {
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int errorCode) { }
                    @Override public void onServiceResolved(NsdServiceInfo i) {
                        if (i.getHost() == null) return;
                        synchronized (found) {
                            found.add(new Host(i.getServiceName(),
                                    i.getHost().getHostAddress(), i.getPort()));
                        }
                    }
                });
            }

            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onDiscoveryStopped(String serviceType) { }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                ui.post(finish);
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) { }
        };
        listenerHolder[0] = listener;

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Exception e) {
            cb.onFinished(new ArrayList<Host>());
            return;
        }
        ui.postDelayed(finish, timeoutMs);
    }
}
