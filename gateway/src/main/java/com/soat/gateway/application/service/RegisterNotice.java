package com.soat.gateway.application.service;

import com.soat.gateway.application.port.EmissionRecord;
import com.soat.gateway.application.port.EmissionRepository;
import com.soat.gateway.application.port.ProviderEventRepository;
import com.soat.gateway.application.port.UnitOfWork;
import com.soat.gateway.domain.emission.Decision;
import com.soat.gateway.domain.emission.ProviderNotice;

import java.util.Optional;

public final class RegisterNotice {

    private final ProviderEventRepository events;
    private final EmissionRepository repository;
    private final DecisionApplier applier;
    private final UnitOfWork unitOfWork;

    public RegisterNotice(ProviderEventRepository events, EmissionRepository repository, DecisionApplier applier,
                          UnitOfWork unitOfWork) {
        this.events = events;
        this.repository = repository;
        this.applier = applier;
        this.unitOfWork = unitOfWork;
    }

    public RegistrationOutcome register(String eventId, String reference, ProviderNotice notice) {
        RegistrationOutcome outcome = unitOfWork.execute(() -> {
            if (!events.registerIfNew(eventId, reference)) {
                return RegistrationOutcome.DUPLICATE;
            }
            Optional<EmissionRecord> record = repository.find(reference);
            if (record.isEmpty()) {
                return RegistrationOutcome.UNKNOWN_REFERENCE;
            }
            Decision decision = record.get().emission().apply(notice);
            if (applier.apply(record.get(), decision, notice.policyNumber())) {
                return RegistrationOutcome.APPLIED;
            }
            return decision instanceof Decision.ManualReview
                    ? RegistrationOutcome.MANUAL_REVIEW
                    : RegistrationOutcome.IGNORED;
        });
        if (outcome == RegistrationOutcome.APPLIED) {
            applier.publishPending(reference);
        }
        return outcome;
    }
}
