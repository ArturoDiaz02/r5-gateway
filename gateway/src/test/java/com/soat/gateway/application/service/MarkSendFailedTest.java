package com.soat.gateway.application.service;

import com.soat.gateway.domain.emission.EmissionStatus;
import org.junit.jupiter.api.Test;

import static com.soat.gateway.application.service.Scenario.REFERENCE;
import static org.assertj.core.api.Assertions.assertThat;

class MarkSendFailedTest {

    @Test
    void requestInTheDeadLetterQueueBecomesSendFailed() {
        Scenario scenario = new Scenario().in(EmissionStatus.REQUESTED);

        scenario.markSendFailed.mark(REFERENCE);

        assertThat(scenario.status()).isEqualTo(EmissionStatus.SEND_FAILED);
    }

    @Test
    void lateDeadLetterDoesNotMoveTheStatusBack() {
        Scenario scenario = new Scenario().in(EmissionStatus.SENT);

        scenario.markSendFailed.mark(REFERENCE);

        assertThat(scenario.status()).isEqualTo(EmissionStatus.SENT);
    }
}
