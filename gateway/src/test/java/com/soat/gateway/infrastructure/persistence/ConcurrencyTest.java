package com.soat.gateway.infrastructure.persistence;

import com.soat.gateway.application.fakes.InMemoryFailedEmissionHandler;
import com.soat.gateway.application.port.ConcurrencyConflict;
import com.soat.gateway.application.port.EmissionRecord;
import com.soat.gateway.application.service.DecisionApplier;
import com.soat.gateway.domain.emission.Decision;
import com.soat.gateway.domain.emission.EmissionStatus;
import com.soat.gateway.domain.emission.ProviderNotice;
import com.soat.gateway.domain.emission.ProviderStatus;
import com.soat.gateway.domain.result.EmissionResult;
import com.soat.gateway.domain.result.ResultType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.soat.gateway.infrastructure.persistence.JdbcRepositoriesTest.record;
import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyTest extends PostgresTestBase {

    private static final int REPETITIONS = 100;
    private static final Instant EXPIRATION = Instant.parse("2026-11-10T13:00:00Z");

    private final JdbcEmissionRepository emissions = new JdbcEmissionRepository(jdbc);
    private final JdbcOutboxRepository outbox = new JdbcOutboxRepository(jdbc);
    private final DecisionApplier applier = new DecisionApplier(emissions, outbox, result -> { },
            new InMemoryFailedEmissionHandler(), unitOfWork, Clock.fixed(EXPIRATION, ZoneOffset.UTC));

    @Test
    void simultaneousApprovalAndExpirationNeverLeaveInconsistentResults() throws Exception {
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < REPETITIONS; i++) {
                String reference = "SOAT-C" + i;
                String policy = "POL-" + i;
                emissions.insertIfAbsent(record(reference, EmissionStatus.SENT));
                EmissionRecord read = emissions.find(reference).orElseThrow();
                Decision approve = read.emission().apply(new ProviderNotice(ProviderStatus.APPROVED, 89_130_000L, policy));
                Decision expire = read.emission().expire(EXPIRATION, null);

                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> a = threads.submit(() -> applyAfterBarrier(start, read, approve, policy));
                Future<?> b = threads.submit(() -> applyAfterBarrier(start, read, expire, null));
                a.get();
                b.get();

                EmissionRecord last = emissions.find(reference).orElseThrow();
                List<EmissionResult> rows = outbox.pending(reference);

                assertThat(last.emission().status()).isIn(EmissionStatus.ISSUED, EmissionStatus.EXPIRED);
                assertThat(rows).as(reference).hasSize(last.history().lastSequence());
                EmissionResult latest = rows.get(rows.size() - 1);
                assertThat(latest.type()).as(reference).isEqualTo(
                        last.emission().status() == EmissionStatus.ISSUED ? ResultType.SUCCESS : ResultType.ERROR);
            }
        }
    }

    @Test
    void simultaneousApprovalsIssueOnlyOnce() throws Exception {
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < REPETITIONS; i++) {
                String reference = "SOAT-D" + i;
                String policy = "POL-" + i;
                emissions.insertIfAbsent(record(reference, EmissionStatus.SENT));
                EmissionRecord read = emissions.find(reference).orElseThrow();
                Decision approve = read.emission().apply(new ProviderNotice(ProviderStatus.APPROVED, 89_130_000L, policy));

                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> a = threads.submit(() -> applyAfterBarrier(start, read, approve, policy));
                Future<?> b = threads.submit(() -> applyAfterBarrier(start, read, approve, policy));
                a.get();
                b.get();

                assertThat(outbox.pending(reference)).as(reference).hasSize(1);
            }
        }
    }

    private Void applyAfterBarrier(CyclicBarrier barrier, EmissionRecord record, Decision decision, String policy)
            throws Exception {
        barrier.await();
        try {
            applier.apply(record, decision, policy);
        } catch (ConcurrencyConflict e) {
            return null;
        }
        return null;
    }
}
