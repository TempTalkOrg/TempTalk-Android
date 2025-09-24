package com.difft.android.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtil {

    public static final String HASH_ALGORITHM_SHA256 = "SHA256";

    public static String calcDataHash(byte[] data, String type)
            throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(type); // lgtm [java/weak-cryptographic-algorithm]
        md.update(data);
        byte[] digest = md.digest();
        return bytesToHex(digest);
    }


    public static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
