package com.soat.gateway.application.service;

import com.soat.gateway.application.fakes.InMemoryDelayedTaskScheduler;
import com.soat.gateway.domain.emission.EmissionStatus;
import com.soat.gateway.domain.emission.ProviderNotice;
import com.soat.gateway.domain.emission.ProviderStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.soat.gateway.application.service.Scenario.PREMIUM;
import static com.soat.gateway.application.service.Scenario.REFERENCE;
import static org.assertj.core.api.Assertions.assertThat;

class RunStatusCheckTest {

    private final Scenario scenario = new Scenario().in(EmissionStatus.SENT);

    @Test
    void pendingSchedulesTheNextCheck() {
        scenario.provider.answerChecksWith(new ProviderNotice(ProviderStatus.PENDING, null));

        scenario.runStatusCheck.run(REFERENCE, 1);

        assertThat(scenario.scheduler.statusCheck(REFERENCE, 2))
                .contains(new InMemoryDelayedTaskScheduler.StatusCheck(REFERENCE, 2, Duration.ofMinutes(1)));
        assertThat(scenario.status()).isEqualTo(EmissionStatus.SENT);
    }

    @Test
    void approvedIssuesAndSchedulesNothingMore() {
        scenario.provider.answerChecksWith(new ProviderNotice(ProviderStatus.APPROVED, PREMIUM, "POL-1"));

        scenario.runStatusCheck.run(REFERENCE, 1);

        assertThat(scenario.status()).isEqualTo(EmissionStatus.ISSUED);
        assertThat(scenario.publisher.published()).hasSize(1);
        assertThat(scenario.scheduler.statusCheck(REFERENCE, 2)).isEmpty();
    }

    @Test
    void exhaustedChecksMoveToReviewWithoutPublishing() {
        scenario.provider.answerChecksWith(new ProviderNotice(ProviderStatus.PENDING, null));

        scenario.runStatusCheck.run(REFERENCE, 5);

        assertThat(scenario.status()).isEqualTo(EmissionStatus.REVIEW_REQUIRED);
        assertThat(scenario.handler.notifications()).containsExactly("reviewRequired " + REFERENCE);
        assertThat(scenario.publisher.published()).isEmpty();
    }

    @Test
    void failedCheckSchedulesTheNextOne() {
        scenario.provider.failChecks(true);

        scenario.runStatusCheck.run(REFERENCE, 2);

        assertThat(scenario.scheduler.statusCheck(REFERENCE, 3)).isPresent();
    }

    @Test
    void finalEmissionIsNotChecked() {
        Scenario issued = new Scenario().in(EmissionStatus.ISSUED);

        issued.runStatusCheck.run(REFERENCE, 1);

        assertThat(issued.provider.checks()).isZero();
    }

    @Test
    void republishesPendingResults() {
        scenario.publisher.failing(true);
        scenario.registerNotice.register("evt_1", REFERENCE,
                new ProviderNotice(ProviderStatus.APPROVED, PREMIUM, "POL-1"));
        scenario.publisher.failing(false);

        scenario.runStatusCheck.run(REFERENCE, 1);

        assertThat(scenario.publisher.published()).hasSize(1);
        assertThat(scenario.outbox.pending(REFERENCE)).isEmpty();
    }
}
