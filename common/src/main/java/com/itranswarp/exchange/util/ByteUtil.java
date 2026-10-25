package com.itranswarp.exchange.util;

import java.util.HexFormat;
import java.util.Objects;

/** Hex encoding for binary values used in logs and messages. */
public final class ByteUtil {
    private ByteUtil() {
    }

    public static String toHexString(byte[] bytes) {
        return HexFormat.of().formatHex(Objects.requireNonNull(bytes, "bytes"));
    }

    public static String toHex(byte value) {
        return HexFormat.of().toHexDigits(value);
    }
}
