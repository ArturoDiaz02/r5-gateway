package com.soat.gateway.domain.emission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.soat.gateway.domain.emission.EmissionStatus.ISSUED;
import static com.soat.gateway.domain.emission.EmissionStatus.REJECTED;
import static com.soat.gateway.domain.emission.EmissionStatus.REQUESTED;
import static com.soat.gateway.domain.emission.EmissionStatus.REVIEW_REQUIRED;
import static com.soat.gateway.domain.emission.EmissionStatus.SEND_FAILED;
import static com.soat.gateway.domain.emission.EmissionStatus.SENT;
import static com.soat.gateway.domain.emission.ProviderStatus.APPROVED;
import static com.soat.gateway.domain.emission.ProviderStatus.PENDING;
import static com.soat.gateway.domain.emission.ProviderStatus.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmissionTest {

    private static final long PREMIUM = 89_130_000L;

    private static Emission in(EmissionStatus status) {
        return new Emission("SOAT-883214", status, PREMIUM, Instant.parse("2026-11-09T14:00:00Z"));
    }

    private static ProviderNotice notice(ProviderStatus status) {
        return new ProviderNotice(status, PREMIUM);
    }

    private static Decision transition(EmissionStatus from, EmissionStatus to) {
        return new Decision.Transition(from, to, TransitionMatrix.allowedOrigins(to));
    }

    @ParameterizedTest
    @EnumSource(value = EmissionStatus.class, names = {"REQUESTED", "SEND_FAILED"})
    void confirmSentMovesToSent(EmissionStatus status) {
        assertThat(in(status).confirmSent()).isEqualTo(transition(status, SENT));
    }

    @ParameterizedTest
    @EnumSource(value = EmissionStatus.class, names = {"SENT", "ISSUED", "EXPIRED"})
    void lateSendConfirmationIsIgnored(EmissionStatus status) {
        assertThat(in(status).confirmSent()).isEqualTo(new Decision.Ignore(IgnoreReason.STALE_NOTICE));
    }

    @Test
    void sendFailureFromRequested() {
        assertThat(in(REQUESTED).registerSendFailure()).isEqualTo(transition(REQUESTED, SEND_FAILED));
    }

    @ParameterizedTest
    @EnumSource(value = EmissionStatus.class, names = {"SENT", "ISSUED"})
    void lateSendFailureIsIgnored(EmissionStatus status) {
        assertThat(in(status).registerSendFailure()).isEqualTo(new Decision.Ignore(IgnoreReason.STALE_NOTICE));
    }

    @ParameterizedTest
    @EnumSource(value = EmissionStatus.class, names = {"REQUESTED", "SENT", "REVIEW_REQUIRED", "SEND_FAILED", "EXPIRED"})
    void approvedIssuesFromAnyNonFinalStatus(EmissionStatus status) {
        assertThat(in(status).apply(notice(APPROVED))).isEqualTo(transition(status, ISSUED));
    }

    @ParameterizedTest
    @EnumSource(value = EmissionStatus.class, names = {"REQUESTED", "SENT", "REVIEW_REQUIRED", "SEND_FAILED", "EXPIRED"})
    void rejectedRejectsFromAnyNonFinalStatus(EmissionStatus status) {
        assertThat(in(status).apply(notice(ProviderStatus.REJECTED))).isEqualTo(transition(status, REJECTED));
    }

    @ParameterizedTest
    @EnumSource(EmissionStatus.class)
    void pendingNeverChangesTheStatus(EmissionStatus status) {
        assertThat(in(status).apply(notice(PENDING)))
                .isEqualTo(new Decision.Ignore(IgnoreReason.PENDING_HAS_NO_EFFECT));
    }

    @Test
    void repeatedApprovalIsIgnored() {
        assertThat(in(ISSUED).apply(notice(APPROVED))).isEqualTo(new Decision.Ignore(IgnoreReason.STALE_NOTICE));
    }

    @Test
    void repeatedRejectionIsIgnored() {
        assertThat(in(REJECTED).apply(notice(ProviderStatus.REJECTED)))
                .isEqualTo(new Decision.Ignore(IgnoreReason.STALE_NOTICE));
    }

    @Test
    void rejectionAfterApprovalIsAConflict() {
        assertThat(in(ISSUED).apply(notice(ProviderStatus.REJECTED)))
                .isEqualTo(new Decision.Ignore(IgnoreReason.PROVIDER_CONFLICT));
    }

    @Test
    void approvalAfterRejectionIsAConflict() {
        assertThat(in(REJECTED).apply(notice(APPROVED)))
                .isEqualTo(new Decision.Ignore(IgnoreReason.PROVIDER_CONFLICT));
    }

    @Test
    void unknownProviderStatusIsIgnored() {
        assertThat(in(SENT).apply(notice(UNKNOWN)))
                .isEqualTo(new Decision.Ignore(IgnoreReason.UNKNOWN_PROVIDER_STATUS));
    }

    @ParameterizedTest
    @ValueSource(longs = {PREMIUM - 1, PREMIUM + 1})
    void amountMismatchGoesToManualReviewWithoutChangingStatus(long amount) {
        Emission sent = in(SENT);
        Decision decision = sent.apply(new ProviderNotice(APPROVED, amount));

        assertThat(decision).isEqualTo(new Decision.ManualReview(ReviewReason.AMOUNT_MISMATCH));
        assertThat(sent.afterDecision(decision).status()).isEqualTo(SENT);
    }

    @Test
    void missingAmountOnApprovalGoesToManualReview() {
        assertThat(in(SENT).apply(new ProviderNotice(APPROVED, null)))
                .isEqualTo(new Decision.ManualReview(ReviewReason.AMOUNT_MISSING));
    }

    @Test
    void rejectionDoesNotValidateTheAmount() {
        assertThat(in(SENT).apply(new ProviderNotice(ProviderStatus.REJECTED, PREMIUM + 1)))
                .isEqualTo(transition(SENT, REJECTED));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveAmountIsInvalid(long amount) {
        assertThatThrownBy(() -> new ProviderNotice(APPROVED, amount)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exhaustedStatusChecksMoveSentToReview() {
        assertThat(in(SENT).exhaustStatusChecks()).isEqualTo(transition(SENT, REVIEW_REQUIRED));
    }

    @ParameterizedTest
    @EnumSource(value = EmissionStatus.class, names = {"REQUESTED", "SEND_FAILED", "ISSUED"})
    void exhaustedStatusChecksOutsideSentAreIgnored(EmissionStatus status) {
        assertThat(in(status).exhaustStatusChecks()).isEqualTo(new Decision.Ignore(IgnoreReason.STALE_NOTICE));
    }

    @Test
    void transitionChangesTheStatus() {
        Emission sent = in(SENT);

        assertThat(sent.afterDecision(sent.apply(notice(APPROVED))).status()).isEqualTo(ISSUED);
    }

    @Test
    void outOfOrderAndDuplicatedNoticesConvergeOnASingleIssue() {
        List<ProviderStatus> notices = List.of(PENDING, APPROVED, PENDING, APPROVED);

        for (List<ProviderStatus> order : permutations(notices)) {
            Emission emission = in(SENT);
            int transitions = 0;
            for (ProviderStatus status : order) {
                Decision decision = emission.apply(notice(status));
                if (decision instanceof Decision.Transition) {
                    transitions++;
                }
                emission = emission.afterDecision(decision);
            }
            assertThat(emission.status()).as("order %s", order).isEqualTo(ISSUED);
            assertThat(transitions).as("order %s", order).isEqualTo(1);
        }
    }

    private static List<List<ProviderStatus>> permutations(List<ProviderStatus> elements) {
        if (elements.size() <= 1) {
            return List.of(elements);
        }
        List<List<ProviderStatus>> result = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            List<ProviderStatus> rest = new ArrayList<>(elements);
            ProviderStatus first = rest.remove(i);
            for (List<ProviderStatus> tail : permutations(rest)) {
                List<ProviderStatus> permutation = new ArrayList<>();
                permutation.add(first);
                permutation.addAll(tail);
                result.add(permutation);
            }
        }
        return result;
    }
}
