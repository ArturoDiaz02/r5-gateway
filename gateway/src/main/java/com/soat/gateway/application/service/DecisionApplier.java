package com.soat.gateway.application.service;

import com.soat.gateway.application.port.EmissionRecord;
import com.soat.gateway.application.port.EmissionRepository;
import com.soat.gateway.application.port.FailedEmissionHandler;
import com.soat.gateway.application.port.OutboxRepository;
import com.soat.gateway.application.port.ResultPublisher;
import com.soat.gateway.application.port.UnitOfWork;
import com.soat.gateway.domain.emission.Decision;
import com.soat.gateway.domain.emission.IgnoreReason;
import com.soat.gateway.domain.result.EmissionResult;
import com.soat.gateway.domain.result.ResultHistory;

import java.time.Clock;
import java.util.Optional;

public final class DecisionApplier {

    private final EmissionRepository repository;
    private final OutboxRepository outbox;
    private final ResultPublisher publisher;
    private final FailedEmissionHandler handler;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public DecisionApplier(EmissionRepository repository, OutboxRepository outbox, ResultPublisher publisher,
                           FailedEmissionHandler handler, UnitOfWork unitOfWork, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.publisher = publisher;
        this.handler = handler;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    public boolean apply(EmissionRecord record, Decision decision, String policyNumber) {
        return switch (decision) {
            case Decision.Transition transition -> transition(record, transition, policyNumber);
            case Decision.ManualReview review -> {
                handler.manualReview(record.reference(), review.reason());
                yield false;
            }
            case Decision.Ignore ignore -> {
                if (ignore.reason() == IgnoreReason.PROVIDER_CONFLICT) {
                    handler.providerConflict(record.reference());
                }
                yield false;
            }
        };
    }

    public void publishPending(String reference) {
        for (EmissionResult result : outbox.pending(reference)) {
            try {
                publisher.publish(result);
            } catch (RuntimeException e) {
                return;
            }
            outbox.markPublished(reference, result.sequence());
        }
    }

    private boolean transition(EmissionRecord record, Decision.Transition transition, String policyNumber) {
        String policy = policyNumber != null ? policyNumber : record.policyNumber();
        Optional<EmissionResult> result = record.history().resultFor(
                transition.to(), record.reference(), record.correlationId(), policy, clock.instant());
        ResultHistory history = result.map(record.history()::after).orElse(record.history());
        return unitOfWork.execute(() -> {
            boolean applied = repository.transition(record.reference(), transition, history, policyNumber);
            if (applied) {
                result.ifPresent(outbox::add);
            }
            return applied;
        });
    }
}
