package com.difft.android.base.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MD5Utils {

    private static final String ENCODING = "UTF-8";

    private static final ThreadLocal<MessageDigest> DIGESTER_CONTEXT = new ThreadLocal<MessageDigest>() {
        protected synchronized MessageDigest initialValue() {
            try {
                return MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }
    };

    public static String md5AndHexStr(String s) {
        return md5AndHex((s.getBytes(StandardCharsets.UTF_8)));
    }

    public static String md5AndHex(byte[] data) {
        return md5AndHex(data, 0, data.length);
    }

    public static String md5AndHex(byte[] data, int start, int len) {
        data = md5(data, start, len);
        return byteToHex(data);
    }

    public static byte[] md5(byte[] data, int start, int len) {
        MessageDigest digester = DIGESTER_CONTEXT.get();
        digester.update(data, start, len);
        return digester.digest();
    }

    public static String byteToHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte t : b) {
            sb.append(String.format("%02x", t));
        }
        return sb.toString();
    }

    public static byte[] md5(byte[]... inputs) throws NoSuchAlgorithmException {
        MessageDigest md5 = MessageDigest.getInstance("md5");
        for (byte[] input : inputs) {
            md5.update(input);
        }
        return md5.digest();
    }
}
