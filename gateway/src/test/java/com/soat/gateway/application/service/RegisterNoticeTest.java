package com.soat.gateway.application.service;

import com.soat.gateway.domain.emission.EmissionStatus;
import com.soat.gateway.domain.emission.ProviderNotice;
import com.soat.gateway.domain.emission.ProviderStatus;
import com.soat.gateway.domain.result.EmissionResult;
import com.soat.gateway.domain.result.ResultType;
import org.junit.jupiter.api.Test;

import static com.soat.gateway.application.service.Scenario.PREMIUM;
import static com.soat.gateway.application.service.Scenario.REFERENCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class RegisterNoticeTest {

    private final Scenario scenario = new Scenario().in(EmissionStatus.SENT);

    private static ProviderNotice approved() {
        return new ProviderNotice(ProviderStatus.APPROVED, PREMIUM, "POL-1");
    }

    @Test
    void newNoticeIssuesAndPublishesSuccess() {
        RegistrationOutcome outcome = scenario.registerNotice.register("evt_1", REFERENCE, approved());

        assertThat(outcome).isEqualTo(RegistrationOutcome.APPLIED);
        assertThat(scenario.status()).isEqualTo(EmissionStatus.ISSUED);
        assertThat(scenario.publisher.published()).singleElement().satisfies(published -> {
            assertThat(published.type()).isEqualTo(ResultType.SUCCESS);
            assertThat(published.sequence()).isEqualTo(1);
            assertThat(published.policyNumber()).isEqualTo("POL-1");
            assertThat(published.correlationId()).isEqualTo("corr-1");
        });
        assertThat(scenario.outbox.pending(REFERENCE)).isEmpty();
    }

    @Test
    void publishesOutsideTheTransaction() {
        scenario.registerNotice.register("evt_1", REFERENCE, approved());

        assertThat(scenario.publisher.insideTransaction()).containsExactly(false);
    }

    @Test
    void repeatedEventIsIgnored() {
        scenario.registerNotice.register("evt_1", REFERENCE, approved());

        RegistrationOutcome repeated = scenario.registerNotice.register("evt_1", REFERENCE, approved());

        assertThat(repeated).isEqualTo(RegistrationOutcome.DUPLICATE);
        assertThat(scenario.outbox.all()).hasSize(1);
    }

    @Test
    void approvalWithAnotherIdAfterIssuingPublishesNothingNew() {
        scenario.registerNotice.register("evt_1", REFERENCE, approved());

        RegistrationOutcome other = scenario.registerNotice.register("evt_2", REFERENCE, approved());

        assertThat(other).isEqualTo(RegistrationOutcome.IGNORED);
        assertThat(scenario.outbox.all()).hasSize(1);
    }

    @Test
    void pendingChangesNothing() {
        RegistrationOutcome outcome = scenario.registerNotice.register("evt_1", REFERENCE,
                new ProviderNotice(ProviderStatus.PENDING, null));

        assertThat(outcome).isEqualTo(RegistrationOutcome.IGNORED);
        assertThat(scenario.status()).isEqualTo(EmissionStatus.SENT);
        assertThat(scenario.publisher.published()).isEmpty();
    }

    @Test
    void amountMismatchGoesToManualReview() {
        RegistrationOutcome outcome = scenario.registerNotice.register("evt_1", REFERENCE,
                new ProviderNotice(ProviderStatus.APPROVED, PREMIUM + 1, "POL-1"));

        assertThat(outcome).isEqualTo(RegistrationOutcome.MANUAL_REVIEW);
        assertThat(scenario.status()).isEqualTo(EmissionStatus.SENT);
        assertThat(scenario.handler.notifications()).containsExactly("manualReview " + REFERENCE + " AMOUNT_MISMATCH");
    }

    @Test
    void unknownReference() {
        assertThat(scenario.registerNotice.register("evt_1", "SOAT-MISSING", approved()))
                .isEqualTo(RegistrationOutcome.UNKNOWN_REFERENCE);
    }

    @Test
    void rejectionAfterIssuingIsAConflict() {
        scenario.registerNotice.register("evt_1", REFERENCE, approved());

        scenario.registerNotice.register("evt_2", REFERENCE, new ProviderNotice(ProviderStatus.REJECTED, PREMIUM));

        assertThat(scenario.handler.notifications()).containsExactly("providerConflict " + REFERENCE);
        assertThat(scenario.status()).isEqualTo(EmissionStatus.ISSUED);
    }

    @Test
    void lateApprovalAfterExpirationPublishesACorrection() {
        Scenario expired = new Scenario().in(EmissionStatus.SENT);
        expired.clock.set(Scenario.EXPIRATION);
        expired.runExpiration.run(REFERENCE);

        expired.registerNotice.register("evt_1", REFERENCE, approved());

        assertThat(expired.publisher.published())
                .extracting(EmissionResult::sequence, EmissionResult::correctsSequence, EmissionResult::type)
                .containsExactly(tuple(1, null, ResultType.ERROR), tuple(2, 1, ResultType.SUCCESS));
    }

    @Test
    void failedPublicationStaysPendingInTheOutbox() {
        scenario.publisher.failing(true);

        RegistrationOutcome outcome = scenario.registerNotice.register("evt_1", REFERENCE, approved());

        assertThat(outcome).isEqualTo(RegistrationOutcome.APPLIED);
        assertThat(scenario.outbox.pending(REFERENCE)).hasSize(1);
    }
}
