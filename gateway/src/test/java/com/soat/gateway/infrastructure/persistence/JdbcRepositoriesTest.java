package com.soat.gateway.infrastructure.persistence;

import com.soat.gateway.application.port.ConcurrencyConflict;
import com.soat.gateway.application.port.EmissionRecord;
import com.soat.gateway.domain.emission.Decision;
import com.soat.gateway.domain.emission.Emission;
import com.soat.gateway.domain.emission.EmissionStatus;
import com.soat.gateway.domain.emission.TransitionMatrix;
import com.soat.gateway.domain.result.EmissionResult;
import com.soat.gateway.domain.result.ResultHistory;
import com.soat.gateway.domain.result.ResultReason;
import com.soat.gateway.domain.result.ResultType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcRepositoriesTest extends PostgresTestBase {

    private static final Instant REQUESTED_AT = Instant.parse("2026-11-09T14:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-09T14:05:00.123Z");

    private final JdbcEmissionRepository emissions = new JdbcEmissionRepository(jdbc);
    private final JdbcProviderEventRepository events = new JdbcProviderEventRepository(jdbc);
    private final JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc);

    static EmissionRecord record(String reference, EmissionStatus status) {
        return new EmissionRecord(new Emission(reference, status, 89_130_000L, REQUESTED_AT),
                "corr-" + reference, ResultHistory.empty(), null);
    }

    private static Decision.Transition to(EmissionStatus from, EmissionStatus target) {
        return new Decision.Transition(from, target, TransitionMatrix.allowedOrigins(target));
    }

    private static EmissionResult result(String reference, int sequence) {
        return new EmissionResult(reference, sequence, ResultType.ERROR, ResultReason.EXPIRED, false,
                null, null, NOW, "corr");
    }

    @Test
    void insertingTwiceLeavesASingleRow() {
        assertThat(emissions.insertIfAbsent(record("SOAT-1", EmissionStatus.REQUESTED))).isTrue();
        assertThat(emissions.insertIfAbsent(record("SOAT-1", EmissionStatus.SENT))).isFalse();

        assertThat(jdbc.sql("SELECT count(*) FROM emission").query(Long.class).single()).isEqualTo(1);
        assertThat(emissions.find("SOAT-1").orElseThrow().emission().status()).isEqualTo(EmissionStatus.REQUESTED);
    }

    @Test
    void findRebuildsTheRecord() {
        emissions.insertIfAbsent(record("SOAT-1", EmissionStatus.REQUESTED));

        assertThat(emissions.find("SOAT-1")).contains(record("SOAT-1", EmissionStatus.REQUESTED));
        assertThat(emissions.find("SOAT-NONE")).isEmpty();
    }

    @Test
    void transitionFromAnAllowedOriginUpdates() {
        emissions.insertIfAbsent(record("SOAT-1", EmissionStatus.SENT));

        boolean applied = emissions.transition("SOAT-1", to(EmissionStatus.SENT, EmissionStatus.ISSUED),
                new ResultHistory(1, false), "POL-1");

        assertThat(applied).isTrue();
        EmissionRecord current = emissions.find("SOAT-1").orElseThrow();
        assertThat(current.emission().status()).isEqualTo(EmissionStatus.ISSUED);
        assertThat(current.history()).isEqualTo(new ResultHistory(1, false));
        assertThat(current.policyNumber()).isEqualTo("POL-1");
    }

    @Test
    void transitionFromADisallowedOriginDoesNotUpdate() {
        emissions.insertIfAbsent(record("SOAT-1", EmissionStatus.ISSUED));

        boolean applied = emissions.transition("SOAT-1", to(EmissionStatus.REQUESTED, EmissionStatus.SENT),
                ResultHistory.empty(), null);

        assertThat(applied).isFalse();
        assertThat(emissions.find("SOAT-1").orElseThrow().emission().status()).isEqualTo(EmissionStatus.ISSUED);
    }

    @Test
    void policyNumberIsNotErasedByNull() {
        emissions.insertIfAbsent(record("SOAT-1", EmissionStatus.EXPIRED));
        emissions.transition("SOAT-1", to(EmissionStatus.EXPIRED, EmissionStatus.ISSUED),
                new ResultHistory(2, false), "POL-1");

        jdbc.sql("UPDATE emission SET status = 'EXPIRED' WHERE reference = 'SOAT-1'").update();
        emissions.transition("SOAT-1", to(EmissionStatus.EXPIRED, EmissionStatus.REJECTED),
                new ResultHistory(2, false), null);

        assertThat(emissions.find("SOAT-1").orElseThrow().policyNumber()).isEqualTo("POL-1");
    }

    @Test
    void duplicatedEventIsNotRegisteredTwice() {
        assertThat(events.registerIfNew("evt_1", "SOAT-1")).isTrue();
        assertThat(events.registerIfNew("evt_1", "SOAT-1")).isFalse();
    }

    @Test
    void outboxReturnsPendingInOrderAndMarksPublished() {
        outbox.add(result("SOAT-1", 2));
        outbox.add(result("SOAT-1", 1));
        outbox.add(result("SOAT-2", 1));

        assertThat(outbox.pending("SOAT-1")).containsExactly(result("SOAT-1", 1), result("SOAT-1", 2));

        outbox.markPublished("SOAT-1", 1);

        assertThat(outbox.pending("SOAT-1")).containsExactly(result("SOAT-1", 2));
    }

    @Test
    void duplicatedSequenceIsAConcurrencyConflict() {
        outbox.add(result("SOAT-1", 1));

        assertThatThrownBy(() -> outbox.add(result("SOAT-1", 1))).isInstanceOf(ConcurrencyConflict.class);
    }

    @Test
    void unitOfWorkRollsBackEverythingOnError() {
        assertThatThrownBy(() -> unitOfWork.execute(() -> {
            emissions.insertIfAbsent(record("SOAT-1", EmissionStatus.REQUESTED));
            outbox.add(result("SOAT-1", 1));
            outbox.add(result("SOAT-1", 1));
            return null;
        })).isInstanceOf(ConcurrencyConflict.class);

        assertThat(emissions.find("SOAT-1")).isEmpty();
        assertThat(outbox.pending("SOAT-1")).isEmpty();
    }
}
