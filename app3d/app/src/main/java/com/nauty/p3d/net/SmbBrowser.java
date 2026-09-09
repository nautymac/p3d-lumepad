package com.nauty.p3d.net;

import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 공유 안의 폴더를 한 번 붙었다 끊는 방식으로 훑는다.
 * 재생 중 스트리밍은 {@link SmbDataSource} 가 따로 맡으므로 여기서는 목록만 본다.
 */
public final class SmbBrowser {

    public static final class Entry {
        public final String name;
        public final boolean directory;
        Entry(String name, boolean directory) { this.name = name; this.directory = directory; }
    }

    private SmbBrowser() {}

    /** path 는 smbj 식 "\\" 구분자. 빈 문자열이면 공유 루트. */
    public static List<Entry> list(String host, int port, String share,
                                    String user, String pass, String path) throws IOException {
        SMBClient client = null;
        try {
            client = new SMBClient();
            Connection connection = client.connect(host, port);
            AuthenticationContext auth = (user == null || user.isEmpty())
                    ? AuthenticationContext.anonymous()
                    : new AuthenticationContext(user, pass == null ? new char[0] : pass.toCharArray(), null);
            Session session = connection.authenticate(auth);
            DiskShare disk = (DiskShare) session.connectShare(share);
            try {
                List<Entry> out = new ArrayList<>();
                for (FileIdBothDirectoryInformation info : disk.list(path)) {
                    String name = info.getFileName();
                    if (".".equals(name) || "..".equals(name)) continue;
                    boolean dir = (info.getFileAttributes()
                            & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                    out.add(new Entry(name, dir));
                }
                return out;
            } finally {
                try { disk.close(); } catch (Exception ignored) { }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("SMB 목록 읽기 실패: " + host + "/" + share + "/" + path, e);
        } finally {
            if (client != null) client.close();
        }
    }
}
