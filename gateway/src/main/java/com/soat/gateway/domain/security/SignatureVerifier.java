package com.soat.gateway.domain.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class SignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Pattern EPOCH_SECONDS = Pattern.compile("\\d{1,10}");
    private static final Pattern HEX_SIGNATURE = Pattern.compile("[0-9a-fA-F]{64}");

    private final Clock clock;
    private final Duration window;
    private final List<byte[]> secrets;

    public SignatureVerifier(Clock clock, Duration window, String currentSecret, String previousSecret) {
        this.clock = clock;
        this.window = window;
        this.secrets = Stream.of(currentSecret, previousSecret)
                .filter(Objects::nonNull)
                .map(secret -> secret.getBytes(StandardCharsets.UTF_8))
                .toList();
    }

    public VerificationResult verify(String timestamp, String signature, byte[] body) {
        if (timestamp == null || signature == null
                || !EPOCH_SECONDS.matcher(timestamp).matches()
                || !HEX_SIGNATURE.matcher(signature).matches()) {
            return VerificationResult.MALFORMED;
        }
        Instant issuedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        if (Duration.between(issuedAt, clock.instant()).abs().compareTo(window) > 0) {
            return VerificationResult.OUTSIDE_WINDOW;
        }
        byte[] received = HexFormat.of().parseHex(signature);
        byte[] signed = concat((timestamp + ".").getBytes(StandardCharsets.UTF_8), body);
        for (byte[] secret : secrets) {
            if (MessageDigest.isEqual(hmac(secret, signed), received)) {
                return VerificationResult.VALID;
            }
        }
        return VerificationResult.INVALID_SIGNATURE;
    }

    private static byte[] concat(byte[] prefix, byte[] body) {
        byte[] result = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(body, 0, result, prefix.length, body.length);
        return result;
    }

    private static byte[] hmac(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
