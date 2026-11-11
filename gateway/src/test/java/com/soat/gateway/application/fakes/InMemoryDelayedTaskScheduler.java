package com.soat.gateway.application.fakes;

import com.soat.gateway.application.port.DelayedTaskScheduler;
import com.soat.gateway.domain.sla.TaskName;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryDelayedTaskScheduler implements DelayedTaskScheduler {

    public record StatusCheck(String reference, int attempt, Duration delay) {
    }

    private final Map<String, Object> tasks = new LinkedHashMap<>();
    private final List<String> journal;

    public InMemoryDelayedTaskScheduler(List<String> journal) {
        this.journal = journal;
    }

    @Override
    public boolean scheduleStatusCheck(String reference, int attempt, Duration delay) {
        journal.add("scheduleStatusCheck " + reference + " " + attempt);
        return tasks.putIfAbsent(TaskName.statusCheck(reference, attempt),
                new StatusCheck(reference, attempt, delay)) == null;
    }

    @Override
    public boolean scheduleExpiration(String reference, Instant at) {
        journal.add("scheduleExpiration " + reference);
        return tasks.putIfAbsent(TaskName.expiration(reference), at) == null;
    }

    public Optional<StatusCheck> statusCheck(String reference, int attempt) {
        return Optional.ofNullable((StatusCheck) tasks.get(TaskName.statusCheck(reference, attempt)));
    }

    public Optional<Instant> expiration(String reference) {
        return Optional.ofNullable((Instant) tasks.get(TaskName.expiration(reference)));
    }

    public int count() {
        return tasks.size();
    }
}
