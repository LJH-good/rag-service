package com.ragservice.storage.util;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

public class ChecksumUtil {

    public record CopyResult(long size, String sha256) {}

    public static CopyResult copyAndSha256(InputStream in, OutputStream out) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        long total = 0;

        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) continue;
            out.write(buf, 0, n);
            md.update(buf, 0, n);
            total += n;
        }
        out.flush();

        return new CopyResult(total, toHex(md.digest()));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
