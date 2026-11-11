package com.soat.gateway.application.service;

import com.soat.gateway.application.port.EmissionRepository;

public final class MarkSendFailed {

    private final EmissionRepository repository;
    private final DecisionApplier applier;

    public MarkSendFailed(EmissionRepository repository, DecisionApplier applier) {
        this.repository = repository;
        this.applier = applier;
    }

    public void mark(String reference) {
        repository.find(reference)
                .ifPresent(record -> applier.apply(record, record.emission().registerSendFailure(), null));
    }
}
