package com.soat.gateway.application.service;

import com.soat.gateway.domain.emission.EmissionStatus;
import com.soat.gateway.domain.emission.ProviderNotice;
import com.soat.gateway.domain.emission.ProviderStatus;
import com.soat.gateway.domain.result.ResultReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.soat.gateway.application.service.Scenario.EXPIRATION;
import static com.soat.gateway.application.service.Scenario.PREMIUM;
import static com.soat.gateway.application.service.Scenario.REFERENCE;
import static org.assertj.core.api.Assertions.assertThat;

class RunExpirationTest {

    private final Scenario scenario = new Scenario().in(EmissionStatus.SENT);

    @Test
    void withoutApprovalExpiresAndPublishesAProvisionalError() {
        scenario.clock.set(EXPIRATION);
        scenario.provider.answerChecksWith(new ProviderNotice(ProviderStatus.PENDING, null));

        scenario.runExpiration.run(REFERENCE);

        assertThat(scenario.status()).isEqualTo(EmissionStatus.EXPIRED);
        assertThat(scenario.publisher.published()).singleElement().satisfies(published -> {
            assertThat(published.reason()).isEqualTo(ResultReason.EXPIRED);
            assertThat(published.definitive()).isFalse();
        });
        assertThat(scenario.handler.notifications()).containsExactly("expired " + REFERENCE);
    }

    @Test
    void approvedFinalCheckIssues() {
        scenario.clock.set(EXPIRATION);
        scenario.provider.answerChecksWith(new ProviderNotice(ProviderStatus.APPROVED, PREMIUM, "POL-1"));

        scenario.runExpiration.run(REFERENCE);

        assertThat(scenario.status()).isEqualTo(EmissionStatus.ISSUED);
        assertThat(scenario.handler.notifications()).isEmpty();
    }

    @Test
    void failedFinalCheckExpires() {
        scenario.clock.set(EXPIRATION);
        scenario.provider.failChecks(true);

        scenario.runExpiration.run(REFERENCE);

        assertThat(scenario.status()).isEqualTo(EmissionStatus.EXPIRED);
    }

    @Test
    void prematureExpirationDoesNothing() {
        scenario.clock.set(EXPIRATION.minus(Duration.ofSeconds(1)));

        scenario.runExpiration.run(REFERENCE);

        assertThat(scenario.status()).isEqualTo(EmissionStatus.SENT);
        assertThat(scenario.provider.checks()).isZero();
    }

    @Test
    void finalEmissionIsNotChecked() {
        Scenario issued = new Scenario().in(EmissionStatus.ISSUED);
        issued.clock.set(EXPIRATION);

        issued.runExpiration.run(REFERENCE);

        assertThat(issued.provider.checks()).isZero();
    }
}
