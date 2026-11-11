package com.soat.gateway.domain.sla;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskNameTest {

    @Test
    void goldenStatusCheckName() {
        assertThat(TaskName.statusCheck("SOAT-883214", 2)).isEqualTo("b24cc06b-SOAT-883214-c2");
    }

    @Test
    void goldenExpirationName() {
        assertThat(TaskName.expiration("SOAT-883214")).isEqualTo("b24cc06b-SOAT-883214-e");
    }

    @Test
    void isDeterministic() {
        assertThat(TaskName.statusCheck("SOAT-1", 1)).isEqualTo(TaskName.statusCheck("SOAT-1", 1));
    }

    @Test
    void changesWithTheAttempt() {
        assertThat(TaskName.statusCheck("SOAT-1", 1)).isNotEqualTo(TaskName.statusCheck("SOAT-1", 2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "SOAT 1", "SOAT/1", "SOAT-ñ"})
    void invalidReference(String reference) {
        assertThatThrownBy(() -> TaskName.statusCheck(reference, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void referenceTooLong() {
        assertThatThrownBy(() -> TaskName.expiration("A".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }
}
