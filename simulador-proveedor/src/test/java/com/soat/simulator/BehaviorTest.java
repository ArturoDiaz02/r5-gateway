package com.soat.simulator;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class BehaviorTest {

    @ParameterizedTest
    @CsvSource({
            "SOAT-1-OK, OK",
            "SOAT-1-REJECT, REJECT",
            "SOAT-1-NOWEBHOOK, NO_WEBHOOK",
            "SOAT-1-DUP, DUPLICATES",
            "SOAT-1-5XX, SERVER_ERROR",
            "SOAT-1-SLOW, SLOW",
            "SOAT-1-NEVER, NEVER",
            "SOAT-1, OK",
            "SOAT-OK-1, OK"})
    void isChosenByTheReferenceSuffix(String reference, Behavior expected) {
        assertThat(Behavior.of(reference)).isEqualTo(expected);
    }
}
