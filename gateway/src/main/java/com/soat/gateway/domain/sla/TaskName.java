package com.soat.gateway.domain.sla;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class TaskName {

    private static final Pattern VALID_REFERENCE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private TaskName() {
    }

    public static String statusCheck(String reference, int attempt) {
        return base(reference) + "-c" + attempt;
    }

    public static String expiration(String reference) {
        return base(reference) + "-e";
    }

    private static String base(String reference) {
        if (!VALID_REFERENCE.matcher(reference).matches()) {
            throw new IllegalArgumentException("reference not valid for a task name: " + reference);
        }
        return hashPrefix(reference) + "-" + reference;
    }

    private static String hashPrefix(String reference) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(reference.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
