package com.nauty.p3d.net;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DLNA(UPnP AV) 를 최소한으로 — description.xml 을 읽어 ContentDirectory 의
 * controlURL 을 찾고, Browse 액션으로 목록을 받는다.
 *
 * 기기 찾기는 {@link Ssdp} 가 맡는다. 거기서 받은 description.xml 의 실제 URL을
 * 그대로 넘기면 되므로, 여기서는 "/description.xml" 경로를 다시 추측하지 않는다 —
 * 제조사마다 그 경로가 다를 수 있어서 실측(SSDP 의 LOCATION) 쪽이 더 정확하다.
 * IP 를 직접 넣는 수동 경로는 findControlUrl(host, port) 로 남겨 둔다.
 *
 * 재생 URL 은 DIDL 의 &lt;res&gt; 에 그대로 들어 있는 평범한 http 주소라 재생
 * 엔진은 손댈 것이 없다 — URL 열기와 같은 길을 탄다.
 */
public final class Dlna {

    public static final class Item {
        public final String id;
        public final String title;
        public final boolean container;
        /** container 면 null. 아니면 바로 재생할 수 있는 http URL. */
        public final String url;

        Item(String id, String title, boolean container, String url) {
            this.id = id;
            this.title = title;
            this.container = container;
            this.url = url;
        }
    }

    private Dlna() {}

    /** 수동 입력용. "/description.xml" 이 표준은 아니지만 흔히 쓰인다. */
    public static String findControlUrl(String host, int port) throws IOException {
        String base = "http://" + host + ":" + port;
        return findControlUrlFromDescription(base + "/description.xml", base);
    }

    /**
     * {@link Ssdp} 가 준 description.xml 의 실제 URL로 찾는다 — 경로를 추측할
     * 필요가 없어 더 정확하다.
     */
    public static String findControlUrl(String descriptionUrl) throws IOException {
        return findControlUrlFromDescription(descriptionUrl, baseOf(descriptionUrl));
    }

    /** description.xml 에서 &lt;friendlyName&gt; 을 읽는다. 목록에 보일 이름이다. */
    public static String fetchFriendlyName(String descriptionUrl) throws IOException {
        String xml = httpGet(descriptionUrl);
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new StringReader(xml));
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "friendlyName".equals(localName(p.getName()))) {
                    return safeText(p);
                }
                ev = p.next();
            }
        } catch (Exception e) {
            throw new IOException("friendlyName 을 못 읽었습니다", e);
        }
        return null;
    }

    private static String findControlUrlFromDescription(String descriptionUrl, String base)
            throws IOException {
        String xml = httpGet(descriptionUrl);
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new StringReader(xml));

            String curType = null, curControl = null;
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = localName(p.getName());
                    if ("service".equals(name)) { curType = null; curControl = null; }
                    else if ("serviceType".equals(name)) curType = safeText(p);
                    else if ("controlURL".equals(name)) curControl = safeText(p);
                } else if (ev == XmlPullParser.END_TAG && "service".equals(localName(p.getName()))) {
                    if (curType != null && curType.contains("ContentDirectory") && curControl != null) {
                        return resolve(base, curControl);
                    }
                }
                ev = p.next();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("description.xml 파싱 실패", e);
        }
        throw new IOException("이 기기에서 ContentDirectory 서비스를 찾지 못했습니다");
    }

    /** URL 에서 scheme://host:port 만 뽑는다 — 상대 controlURL 을 절대 경로로 바꿀 기준. */
    private static String baseOf(String url) throws IOException {
        try {
            URL u = new URL(url);
            int port = u.getPort();
            return u.getProtocol() + "://" + u.getHost() + (port >= 0 ? ":" + port : "");
        } catch (Exception e) {
            throw new IOException("잘못된 URL: " + url, e);
        }
    }

    /** objectId 아래 항목을 한 단계만 나열한다 (BrowseDirectChildren). */
    public static List<Item> browse(String controlUrl, String objectId) throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">"
                + "<ObjectID>" + escapeXml(objectId) + "</ObjectID>"
                + "<BrowseFlag>BrowseDirectChildren</BrowseFlag>"
                + "<Filter>*</Filter><StartingIndex>0</StartingIndex>"
                + "<RequestedCount>0</RequestedCount><SortCriteria></SortCriteria>"
                + "</u:Browse></s:Body></s:Envelope>";

        String resp = httpPost(controlUrl, body,
                "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"");

        // SOAP 응답 안의 <Result> 는 DIDL-Lite XML 이 다시 이스케이프돼 들어 있다.
        String didl = extractTag(resp, "Result");
        if (didl == null) throw new IOException("Browse 응답에 Result 가 없습니다");
        return parseDidl(unescapeXml(didl));
    }

    private static List<Item> parseDidl(String didl) throws IOException {
        List<Item> out = new ArrayList<>();
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(new StringReader(didl));

            int ev = p.getEventType();
            String id = null, title = null, url = null;
            boolean inContainer = false, inItem = false;
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = localName(p.getName());
                    if ("container".equals(name)) {
                        inContainer = true;
                        id = p.getAttributeValue(null, "id");
                        title = null;
                    } else if ("item".equals(name)) {
                        inItem = true;
                        id = p.getAttributeValue(null, "id");
                        title = null;
                        url = null;
                    } else if ("title".equals(name) && (inContainer || inItem)) {
                        title = safeText(p);
                    } else if ("res".equals(name) && inItem) {
                        url = safeText(p);
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = localName(p.getName());
                    if ("container".equals(name) && inContainer) {
                        out.add(new Item(id, title == null ? id : title, true, null));
                        inContainer = false;
                    } else if ("item".equals(name) && inItem) {
                        if (url != null) out.add(new Item(id, title == null ? id : title, false, url));
                        inItem = false;
                    }
                }
                ev = p.next();
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("DIDL 파싱 실패", e);
        }
        return out;
    }

    // ------------------------------------------------------------ HTTP

    private static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        try {
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String httpPost(String url, String body, String soapAction) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", soapAction);
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try {
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String resp = readAll(in);
            if (code < 200 || code >= 300) throw new IOException("DLNA 서버 응답 " + code + ": " + resp);
            return resp;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String safeText(XmlPullParser p) throws Exception {
        String t = p.nextText();
        return t == null ? null : t.trim();
    }

    /** 네임스페이스 접두어를 무시한다 ("dc:title" -> "title"). */
    private static String localName(String qName) {
        int c = qName.indexOf(':');
        return c >= 0 ? qName.substring(c + 1) : qName;
    }

    private static String resolve(String base, String controlUrl) {
        if (controlUrl.startsWith("http://") || controlUrl.startsWith("https://")) return controlUrl;
        return controlUrl.startsWith("/") ? base + controlUrl : base + "/" + controlUrl;
    }

    private static String escapeXml(String s) {
        return s == null ? "0" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    /** SOAP 봉투 안에서 &lt;Tag&gt;...&lt;/Tag&gt; 하나를 꺼낸다. 중첩 태그가 없는 값에만 쓴다. */
    private static String extractTag(String xml, String tag) {
        int i = xml.indexOf("<" + tag);
        if (i < 0) return null;
        i = xml.indexOf('>', i);
        if (i < 0) return null;
        int j = xml.indexOf("</" + tag + ">", i);
        if (j < 0) return null;
        return xml.substring(i + 1, j);
    }
}
