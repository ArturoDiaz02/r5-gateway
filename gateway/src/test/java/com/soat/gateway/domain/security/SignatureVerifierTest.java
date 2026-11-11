package com.soat.gateway.domain.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureVerifierTest {

    private static final String SECRET_V1 = "secreto-prueba-v1";
    private static final String SECRET_V2 = "secreto-prueba-v2";
    private static final long TS = 1794233002L;
    private static final String TIMESTAMP = String.valueOf(TS);
    private static final String BODY = "{\"id\":\"evt_01JC8Q2W7M\",\"tipo\":\"pago.actualizado\","
            + "\"creado_en\":\"2026-11-09T14:03:21.482Z\",\"data\":{\"referencia\":\"SOAT-883214\","
            + "\"estado\":\"APROBADO\",\"monto_centavos\":89130000}}";
    private static final String SIGNATURE = "6e4c5cd3315182229277b2c5fac58caeb142de384638706b51afd07ace65a6d3";
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private static SignatureVerifier verifier(long nowEpoch, String current, String previous) {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(nowEpoch), ZoneOffset.UTC);
        return new SignatureVerifier(clock, WINDOW, current, previous);
    }

    private static SignatureVerifier verifier() {
        return verifier(TS + 1, SECRET_V1, null);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void validGoldenVector() {
        assertThat(verifier().verify(TIMESTAMP, SIGNATURE, bytes(BODY))).isEqualTo(VerificationResult.VALID);
    }

    @Test
    void tamperedSignature() {
        String tampered = SIGNATURE.substring(0, 63) + "4";

        assertThat(verifier().verify(TIMESTAMP, tampered, bytes(BODY))).isEqualTo(VerificationResult.INVALID_SIGNATURE);
    }

    @Test
    void tamperedBody() {
        String tampered = BODY.replace("89130000", "89130001");

        assertThat(verifier().verify(TIMESTAMP, SIGNATURE, bytes(tampered))).isEqualTo(VerificationResult.INVALID_SIGNATURE);
    }

    @Test
    void reserializedBodyDoesNotVerify() {
        String reserialized = BODY.replace("\"id\":", "\"id\": ");

        assertThat(verifier().verify(TIMESTAMP, SIGNATURE, bytes(reserialized)))
                .isEqualTo(VerificationResult.INVALID_SIGNATURE);
    }

    @Test
    void emptyBody() {
        assertThat(verifier().verify(TIMESTAMP, SIGNATURE, new byte[0])).isEqualTo(VerificationResult.INVALID_SIGNATURE);
    }

    @ParameterizedTest
    @ValueSource(longs = {-301, 301})
    void timestampOutsideTheWindow(long offset) {
        assertThat(verifier(TS + offset, SECRET_V1, null).verify(TIMESTAMP, SIGNATURE, bytes(BODY)))
                .isEqualTo(VerificationResult.OUTSIDE_WINDOW);
    }

    @ParameterizedTest
    @ValueSource(longs = {-300, 300})
    void windowBoundariesAreValid(long offset) {
        assertThat(verifier(TS + offset, SECRET_V1, null).verify(TIMESTAMP, SIGNATURE, bytes(BODY)))
                .isEqualTo(VerificationResult.VALID);
    }

    @Test
    void previousSecretIsAcceptedDuringRotation() {
        assertThat(verifier(TS + 1, SECRET_V2, SECRET_V1).verify(TIMESTAMP, SIGNATURE, bytes(BODY)))
                .isEqualTo(VerificationResult.VALID);
    }

    @Test
    void unknownSecretIsRejected() {
        assertThat(verifier(TS + 1, SECRET_V2, "secreto-prueba-v3").verify(TIMESTAMP, SIGNATURE, bytes(BODY)))
                .isEqualTo(VerificationResult.INVALID_SIGNATURE);
    }

    @Test
    void upperCaseHexIsValid() {
        assertThat(verifier().verify(TIMESTAMP, SIGNATURE.toUpperCase(), bytes(BODY))).isEqualTo(VerificationResult.VALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "6e4c5cd3315182229277b2c5fac58caeb142de384638706b51afd07ace65a6d",
            "6e4c5cd3315182229277b2c5fac58caeb142de384638706b51afd07ace65a6d30",
            "zz4c5cd3315182229277b2c5fac58caeb142de384638706b51afd07ace65a6d3",
            "sha256=6e4c5cd3315182229277b2c5fac58caeb142de384638706b51afd07ace65a6d3",
            ""
    })
    void malformedSignature(String signature) {
        assertThat(verifier().verify(TIMESTAMP, signature, bytes(BODY))).isEqualTo(VerificationResult.MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "1794233002.5", "1794233002000", " 1794233002", "+1794233002", "-1794233002"})
    void malformedTimestamp(String timestamp) {
        assertThat(verifier().verify(timestamp, SIGNATURE, bytes(BODY))).isEqualTo(VerificationResult.MALFORMED);
    }

    @Test
    void missingHeaders() {
        assertThat(verifier().verify(null, SIGNATURE, bytes(BODY))).isEqualTo(VerificationResult.MALFORMED);
        assertThat(verifier().verify(TIMESTAMP, null, bytes(BODY))).isEqualTo(VerificationResult.MALFORMED);
    }
}
