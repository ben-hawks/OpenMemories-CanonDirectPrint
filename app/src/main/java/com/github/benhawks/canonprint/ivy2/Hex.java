package com.github.benhawks.canonprint.ivy2;

final class Hex {
    private Hex() {}

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(DIGITS[(b >> 4) & 0xF]);
            sb.append(DIGITS[b & 0xF]);
        }
        return sb.toString();
    }
}
