package com.soat.gateway.application.service;

import com.soat.gateway.application.port.DelayedTaskScheduler;
import com.soat.gateway.application.port.EmissionProvider;
import com.soat.gateway.application.port.EmissionRecord;
import com.soat.gateway.application.port.EmissionRepository;
import com.soat.gateway.application.port.EmissionRequest;
import com.soat.gateway.application.port.ProviderFailure;
import com.soat.gateway.application.port.RequestQueue;
import com.soat.gateway.application.port.UnitOfWork;
import com.soat.gateway.domain.emission.Emission;
import com.soat.gateway.domain.emission.EmissionStatus;
import com.soat.gateway.domain.result.ResultHistory;
import com.soat.gateway.domain.sla.StatusCheckSchedule;

import java.util.EnumSet;
import java.util.Set;

public final class ProcessRequest {

    private static final Set<EmissionStatus> SENDABLE = EnumSet.of(EmissionStatus.REQUESTED, EmissionStatus.SEND_FAILED);

    private final EmissionRepository repository;
    private final DelayedTaskScheduler scheduler;
    private final EmissionProvider provider;
    private final RequestQueue queue;
    private final DecisionApplier applier;
    private final UnitOfWork unitOfWork;
    private final StatusCheckSchedule schedule;
    private final RetryPolicy retryPolicy;

    public ProcessRequest(EmissionRepository repository, DelayedTaskScheduler scheduler, EmissionProvider provider,
                          RequestQueue queue, DecisionApplier applier, UnitOfWork unitOfWork,
                          StatusCheckSchedule schedule, RetryPolicy retryPolicy) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.provider = provider;
        this.queue = queue;
        this.applier = applier;
        this.unitOfWork = unitOfWork;
        this.schedule = schedule;
        this.retryPolicy = retryPolicy;
    }

    public void process(EmissionRequest request, int attempt) {
        EmissionRecord record = unitOfWork.execute(() -> {
            repository.insertIfAbsent(newRecord(request));
            return repository.find(request.reference()).orElseThrow();
        });
        if (!SENDABLE.contains(record.emission().status())) {
            return;
        }
        scheduler.scheduleStatusCheck(request.reference(), 1, schedule.delay(1));
        scheduler.scheduleExpiration(request.reference(), record.emission().sla().expirationInstant());
        try {
            provider.requestEmission(request);
        } catch (ProviderFailure e) {
            if (attempt < retryPolicy.maxAttempts()) {
                queue.retry(request, attempt + 1, retryPolicy.delay());
            } else {
                queue.sendToDeadLetter(request);
            }
            return;
        }
        applier.apply(record, record.emission().confirmSent(), null);
    }

    private static EmissionRecord newRecord(EmissionRequest request) {
        Emission emission = new Emission(request.reference(), EmissionStatus.REQUESTED,
                request.quotedPremiumCents(), request.requestedAt());
        return new EmissionRecord(emission, request.correlationId(), ResultHistory.empty(), null);
    }
}
