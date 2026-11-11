package com.soat.gateway.application.service;

import com.soat.gateway.application.fakes.ControllableClock;
import com.soat.gateway.application.fakes.FakeEmissionProvider;
import com.soat.gateway.application.fakes.InMemoryDelayedTaskScheduler;
import com.soat.gateway.application.fakes.InMemoryEmissionRepository;
import com.soat.gateway.application.fakes.InMemoryFailedEmissionHandler;
import com.soat.gateway.application.fakes.InMemoryOutbox;
import com.soat.gateway.application.fakes.InMemoryProviderEventRepository;
import com.soat.gateway.application.fakes.InMemoryRequestQueue;
import com.soat.gateway.application.fakes.InMemoryResultPublisher;
import com.soat.gateway.application.fakes.InMemoryUnitOfWork;
import com.soat.gateway.application.port.EmissionRequest;
import com.soat.gateway.domain.emission.EmissionStatus;
import com.soat.gateway.domain.sla.StatusCheckSchedule;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class Scenario {

    static final String REFERENCE = "SOAT-883214";
    static final long PREMIUM = 89_130_000L;
    static final Instant REQUESTED_AT = Instant.parse("2026-11-09T14:00:00Z");
    static final Instant EXPIRATION = Instant.parse("2026-11-10T13:00:00Z");

    final List<String> journal = new ArrayList<>();
    final ControllableClock clock = new ControllableClock(REQUESTED_AT.plusSeconds(1));
    final InMemoryUnitOfWork unitOfWork = new InMemoryUnitOfWork();
    final InMemoryEmissionRepository repository = new InMemoryEmissionRepository(journal);
    final InMemoryProviderEventRepository events = new InMemoryProviderEventRepository();
    final InMemoryOutbox outbox = new InMemoryOutbox();
    final InMemoryResultPublisher publisher = new InMemoryResultPublisher(unitOfWork);
    final InMemoryDelayedTaskScheduler scheduler = new InMemoryDelayedTaskScheduler(journal);
    final InMemoryRequestQueue queue = new InMemoryRequestQueue();
    final FakeEmissionProvider provider = new FakeEmissionProvider(journal, unitOfWork);
    final InMemoryFailedEmissionHandler handler = new InMemoryFailedEmissionHandler();

    final DecisionApplier applier = new DecisionApplier(repository, outbox, publisher, handler, unitOfWork, clock);
    final ProcessRequest processRequest = new ProcessRequest(repository, scheduler, provider, queue, applier,
            unitOfWork, StatusCheckSchedule.defaults(), new RetryPolicy(5, Duration.ofSeconds(10)));
    final RegisterNotice registerNotice = new RegisterNotice(events, repository, applier, unitOfWork);
    final RunStatusCheck runStatusCheck = new RunStatusCheck(repository, provider, scheduler, applier, handler,
            StatusCheckSchedule.defaults());
    final RunExpiration runExpiration = new RunExpiration(repository, provider, applier, handler, clock);
    final MarkSendFailed markSendFailed = new MarkSendFailed(repository, applier);

    static EmissionRequest request() {
        return new EmissionRequest(REFERENCE, "corr-1", REQUESTED_AT, PREMIUM, "ABC123");
    }

    Scenario in(EmissionStatus status) {
        processRequest.process(request(), 1);
        repository.forceStatus(REFERENCE, status);
        journal.clear();
        return this;
    }

    EmissionStatus status() {
        return repository.status(REFERENCE);
    }
}
