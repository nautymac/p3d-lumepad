package com.nauty.p3d.net;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DLNA(UPnP) 서버를 SSDP M-SEARCH 로 찾는다. IP 를 몰라도 같은 네트워크 안의
 * 미디어 서버를 목록으로 보여줄 수 있다 (사용자 요청 — SMB/DLNA 모두 찾은 것을
 * 고르게 해 달라는 것). 응답의 LOCATION 이 description.xml 의 실제 URL이라
 * Dlna.findControlUrl(String) 에 그대로 넘기면 된다.
 */
public final class Ssdp {

    public static final class Device {
        /** description.xml 의 실제 URL. SSDP 가 알려준 그대로라 경로를 추측할 필요가 없다. */
        public final String location;
        /** 나중에 채운다. 못 읽으면 location 을 그대로 이름으로 쓴다. */
        public String friendlyName;

        Device(String location) { this.location = location; }
    }

    private Ssdp() {}

    /** timeoutMs 동안 응답을 모은다. 실패해도 예외 대신 빈 목록을 돌려준다. */
    public static List<Device> discover(Context ctx, long timeoutMs) {
        WifiManager wifi = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wifi == null ? null : wifi.createMulticastLock("p3d-ssdp");
        if (lock != null) {
            lock.setReferenceCounted(true);
            lock.acquire();
        }

        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket();
            socket.setSoTimeout(500);   // receive() 를 짧게 끊어 데드라인을 직접 관리한다

            InetAddress group = InetAddress.getByName("239.255.255.250");
            String msg = "M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: 239.255.255.250:1900\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 2\r\n"
                    + "ST: ssdp:all\r\n\r\n";
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            // 두 번 보낸다 — UDP 는 유실될 수 있고, 느린 기기까지 잡으려면 여유가 있어야 한다.
            socket.send(new DatagramPacket(data, data.length, group, 1900));
            socket.send(new DatagramPacket(data, data.length, group, 1900));

            Map<String, Device> found = new LinkedHashMap<>();
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[8192];
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket resp = new DatagramPacket(buf, buf.length);
                    socket.receive(resp);
                    String text = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                    String location = header(text, "LOCATION");
                    if (location != null && !found.containsKey(location)) {
                        found.put(location, new Device(location));
                    }
                } catch (SocketTimeoutException ignored) {
                    // 데드라인까지 계속 기다린다 (짧은 타임아웃으로 반복 폴링)
                }
            }
            return new ArrayList<>(found.values());
        } catch (IOException e) {
            return new ArrayList<>();
        } finally {
            if (socket != null) socket.close();
            if (lock != null) lock.release();
        }
    }

    private static final Pattern HEADER = Pattern.compile("^([A-Za-z0-9-]+):\\s*(.*?)\\s*$",
            Pattern.MULTILINE);

    private static String header(String text, String name) {
        Matcher m = HEADER.matcher(text);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(name)) return m.group(2);
        }
        return null;
    }
}
