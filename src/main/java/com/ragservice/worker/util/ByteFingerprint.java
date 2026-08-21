package com.ragservice.worker.util;

import org.apache.commons.codec.digest.DigestUtils;

/**
 * 업로드 바이트 무결성 추적용 fingerprint (sha256, head hex).
 */
public final class ByteFingerprint {

    public static final int HEAD_BYTES = 64;

    private ByteFingerprint() {
    }

    public static String sha256Hex(byte[] bytes) {
        return DigestUtils.sha256Hex(bytes);
    }

    public static String headHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        int len = Math.min(bytes.length, HEAD_BYTES);
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02x", bytes[i] & 0xff));
        }
        return sb.toString();
    }
}
