package com.soat.gateway.domain.emission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.soat.gateway.domain.emission.EmissionStatus.EXPIRED;
import static com.soat.gateway.domain.emission.EmissionStatus.ISSUED;
import static com.soat.gateway.domain.emission.EmissionStatus.REJECTED;
import static com.soat.gateway.domain.emission.EmissionStatus.REQUESTED;
import static com.soat.gateway.domain.emission.EmissionStatus.REVIEW_REQUIRED;
import static com.soat.gateway.domain.emission.EmissionStatus.SEND_FAILED;
import static com.soat.gateway.domain.emission.EmissionStatus.SENT;
import static org.assertj.core.api.Assertions.assertThat;

class TransitionMatrixTest {

    private static final Set<String> ALLOWED = Set.of(
            "REQUESTED>SENT", "REQUESTED>SEND_FAILED", "REQUESTED>EXPIRED",
            "REQUESTED>ISSUED", "REQUESTED>REJECTED",
            "SENT>REVIEW_REQUIRED", "SENT>EXPIRED", "SENT>ISSUED", "SENT>REJECTED",
            "REVIEW_REQUIRED>EXPIRED", "REVIEW_REQUIRED>ISSUED", "REVIEW_REQUIRED>REJECTED",
            "SEND_FAILED>SENT", "SEND_FAILED>EXPIRED", "SEND_FAILED>ISSUED", "SEND_FAILED>REJECTED",
            "EXPIRED>ISSUED", "EXPIRED>REJECTED");

    static Stream<Arguments> allPairs() {
        return Arrays.stream(EmissionStatus.values())
                .flatMap(from -> Arrays.stream(EmissionStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void onlyAllowsTheTransitionsInTheMatrix(EmissionStatus from, EmissionStatus to) {
        assertThat(TransitionMatrix.allows(from, to)).isEqualTo(ALLOWED.contains(from + ">" + to));
    }

    @Test
    void theMatrixHasEighteenTransitions() {
        long allowed = allPairs()
                .filter(pair -> TransitionMatrix.allows((EmissionStatus) pair.get()[0], (EmissionStatus) pair.get()[1]))
                .count();

        assertThat(allowed).isEqualTo(18);
    }

    @ParameterizedTest
    @EnumSource(EmissionStatus.class)
    void allowedOriginsMatchAllows(EmissionStatus target) {
        Set<EmissionStatus> expected = Arrays.stream(EmissionStatus.values())
                .filter(origin -> ALLOWED.contains(origin + ">" + target))
                .collect(Collectors.toSet());

        assertThat(TransitionMatrix.allowedOrigins(target)).isEqualTo(expected);
    }

    @Test
    void originsTowardsIssued() {
        assertThat(TransitionMatrix.allowedOrigins(ISSUED))
                .containsExactlyInAnyOrder(REQUESTED, SENT, REVIEW_REQUIRED, SEND_FAILED, EXPIRED);
    }

    @Test
    void requestedIsOnlyReachedByCreation() {
        assertThat(TransitionMatrix.allowedOrigins(REQUESTED)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(EmissionStatus.class)
    void onlyIssuedAndRejectedAreFinal(EmissionStatus status) {
        assertThat(status.isFinal()).isEqualTo(status == ISSUED || status == REJECTED);
    }
}
