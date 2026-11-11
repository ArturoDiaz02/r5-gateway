package com.soat.gateway.domain.result;

import com.soat.gateway.domain.emission.EmissionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static com.soat.gateway.domain.emission.EmissionStatus.EXPIRED;
import static com.soat.gateway.domain.emission.EmissionStatus.ISSUED;
import static com.soat.gateway.domain.emission.EmissionStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultHistoryTest {

    private static final String REFERENCE = "SOAT-883214";
    private static final String CORRELATION = "corr-1";
    private static final Instant NOW = Instant.parse("2026-11-09T14:05:00Z");

    private static EmissionResult result(ResultHistory history, EmissionStatus target, String policy) {
        return history.resultFor(target, REFERENCE, CORRELATION, policy, NOW).orElseThrow();
    }

    @Test
    void firstIssueIsADefinitiveSuccess() {
        assertThat(result(ResultHistory.empty(), ISSUED, "POL-1")).isEqualTo(new EmissionResult(
                REFERENCE, 1, ResultType.SUCCESS, null, true, null, "POL-1", NOW, CORRELATION));
    }

    @Test
    void rejectionIsADefinitiveError() {
        assertThat(result(ResultHistory.empty(), REJECTED, null)).isEqualTo(new EmissionResult(
                REFERENCE, 1, ResultType.ERROR, ResultReason.REJECTED, true, null, null, NOW, CORRELATION));
    }

    @Test
    void expirationIsAProvisionalError() {
        assertThat(result(ResultHistory.empty(), EXPIRED, null)).isEqualTo(new EmissionResult(
                REFERENCE, 1, ResultType.ERROR, ResultReason.EXPIRED, false, null, null, NOW, CORRELATION));
    }

    @Test
    void lateApprovalCorrectsTheProvisionalResult() {
        ResultHistory afterExpiring = ResultHistory.empty().after(result(ResultHistory.empty(), EXPIRED, null));

        EmissionResult correction = result(afterExpiring, ISSUED, "POL-1");

        assertThat(correction.sequence()).isEqualTo(2);
        assertThat(correction.correctsSequence()).isEqualTo(1);
        assertThat(correction.type()).isEqualTo(ResultType.SUCCESS);
        assertThat(correction.definitive()).isTrue();
    }

    @Test
    void lateRejectionCorrectsTheProvisionalResult() {
        ResultHistory afterExpiring = ResultHistory.empty().after(result(ResultHistory.empty(), EXPIRED, null));

        EmissionResult correction = result(afterExpiring, REJECTED, null);

        assertThat(correction.sequence()).isEqualTo(2);
        assertThat(correction.correctsSequence()).isEqualTo(1);
        assertThat(correction.reason()).isEqualTo(ResultReason.REJECTED);
    }

    @ParameterizedTest
    @EnumSource(value = EmissionStatus.class, names = {"REQUESTED", "SENT", "SEND_FAILED", "REVIEW_REQUIRED"})
    void intermediateStatusesPublishNothing(EmissionStatus target) {
        assertThat(ResultHistory.empty().resultFor(target, REFERENCE, CORRELATION, null, NOW)).isEmpty();
    }

    @Test
    void aDefinitiveResultIsNeverCorrected() {
        ResultHistory afterIssuing = ResultHistory.empty().after(result(ResultHistory.empty(), ISSUED, "POL-1"));

        assertThatThrownBy(() -> afterIssuing.resultFor(REJECTED, REFERENCE, CORRELATION, null, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thereAreNeverTwoProvisionalResults() {
        ResultHistory afterExpiring = ResultHistory.empty().after(result(ResultHistory.empty(), EXPIRED, null));

        assertThatThrownBy(() -> afterExpiring.resultFor(EXPIRED, REFERENCE, CORRELATION, null, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void historyRemembersLastSequenceAndWhetherItWasProvisional() {
        assertThat(ResultHistory.empty().after(result(ResultHistory.empty(), EXPIRED, null)))
                .isEqualTo(new ResultHistory(1, true));
        assertThat(ResultHistory.empty().after(result(ResultHistory.empty(), ISSUED, "POL-1")))
                .isEqualTo(new ResultHistory(1, false));
    }
}
