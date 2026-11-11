package com.soat.gateway.domain.emission;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderStatusTest {

    @ParameterizedTest
    @CsvSource({"APROBADO, APPROVED", "PENDIENTE, PENDING", "RECHAZADO, REJECTED",
            "ANULADO, UNKNOWN", "aprobado, UNKNOWN", "APPROVED, UNKNOWN"})
    void mapsTheProviderWireValue(String value, ProviderStatus expected) {
        assertThat(ProviderStatus.fromWire(value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingValueIsUnknown(String value) {
        assertThat(ProviderStatus.fromWire(value)).isEqualTo(ProviderStatus.UNKNOWN);
    }
}
