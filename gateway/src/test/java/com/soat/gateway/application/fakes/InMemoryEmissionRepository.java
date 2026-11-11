package com.soat.gateway.application.fakes;

import com.soat.gateway.application.port.EmissionRecord;
import com.soat.gateway.application.port.EmissionRepository;
import com.soat.gateway.domain.emission.Decision;
import com.soat.gateway.domain.emission.Emission;
import com.soat.gateway.domain.emission.EmissionStatus;
import com.soat.gateway.domain.result.ResultHistory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryEmissionRepository implements EmissionRepository {

    private final Map<String, EmissionRecord> records = new HashMap<>();
    private final List<String> journal;

    public InMemoryEmissionRepository(List<String> journal) {
        this.journal = journal;
    }

    @Override
    public boolean insertIfAbsent(EmissionRecord record) {
        journal.add("insert " + record.reference());
        return records.putIfAbsent(record.reference(), record) == null;
    }

    @Override
    public Optional<EmissionRecord> find(String reference) {
        return Optional.ofNullable(records.get(reference));
    }

    @Override
    public boolean transition(String reference, Decision.Transition transition, ResultHistory history,
                              String policyNumber) {
        EmissionRecord current = records.get(reference);
        if (current == null || !transition.allowedOrigins().contains(current.emission().status())) {
            return false;
        }
        Emission emission = current.emission();
        records.put(reference, new EmissionRecord(
                new Emission(reference, transition.to(), emission.quotedPremiumCents(), emission.requestedAt()),
                current.correlationId(),
                history,
                policyNumber != null ? policyNumber : current.policyNumber()));
        return true;
    }

    public void forceStatus(String reference, EmissionStatus status) {
        EmissionRecord current = records.get(reference);
        Emission emission = current.emission();
        records.put(reference, new EmissionRecord(
                new Emission(reference, status, emission.quotedPremiumCents(), emission.requestedAt()),
                current.correlationId(), current.history(), current.policyNumber()));
    }

    public EmissionStatus status(String reference) {
        return records.get(reference).emission().status();
    }
}
