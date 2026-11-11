package com.soat.gateway.application.service;

import com.soat.gateway.application.port.DelayedTaskScheduler;
import com.soat.gateway.application.port.EmissionProvider;
import com.soat.gateway.application.port.EmissionRecord;
import com.soat.gateway.application.port.EmissionRepository;
import com.soat.gateway.application.port.FailedEmissionHandler;
import com.soat.gateway.application.port.ProviderFailure;
import com.soat.gateway.domain.emission.ProviderNotice;
import com.soat.gateway.domain.sla.StatusCheckSchedule;

import java.util.Optional;

public final class RunStatusCheck {

    private final EmissionRepository repository;
    private final EmissionProvider provider;
    private final DelayedTaskScheduler scheduler;
    private final DecisionApplier applier;
    private final FailedEmissionHandler handler;
    private final StatusCheckSchedule schedule;

    public RunStatusCheck(EmissionRepository repository, EmissionProvider provider, DelayedTaskScheduler scheduler,
                          DecisionApplier applier, FailedEmissionHandler handler, StatusCheckSchedule schedule) {
        this.repository = repository;
        this.provider = provider;
        this.scheduler = scheduler;
        this.applier = applier;
        this.handler = handler;
        this.schedule = schedule;
    }

    public void run(String reference, int attempt) {
        applier.publishPending(reference);
        Optional<EmissionRecord> record = repository.find(reference).filter(r -> !r.emission().status().isFinal());
        if (record.isEmpty()) {
            return;
        }
        check(reference).ifPresent(notice ->
                applier.apply(record.get(), record.get().emission().apply(notice), notice.policyNumber()));
        applier.publishPending(reference);
        EmissionRecord current = repository.find(reference).orElseThrow();
        if (current.emission().status().isFinal()) {
            return;
        }
        schedule.next(attempt).ifPresentOrElse(
                next -> scheduler.scheduleStatusCheck(reference, next, schedule.delay(next)),
                () -> {
                    if (applier.apply(current, current.emission().exhaustStatusChecks(), null)) {
                        handler.reviewRequired(reference);
                    }
                });
    }

    private Optional<ProviderNotice> check(String reference) {
        try {
            return provider.checkStatus(reference);
        } catch (ProviderFailure e) {
            return Optional.empty();
        }
    }
}
