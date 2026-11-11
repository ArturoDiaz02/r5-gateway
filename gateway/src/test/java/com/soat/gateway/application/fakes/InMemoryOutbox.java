package com.soat.gateway.application.fakes;

import com.soat.gateway.application.port.OutboxRepository;
import com.soat.gateway.domain.result.EmissionResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class InMemoryOutbox implements OutboxRepository {

    private final List<EmissionResult> rows = new ArrayList<>();
    private final Set<String> published = new HashSet<>();

    @Override
    public void add(EmissionResult result) {
        boolean duplicated = rows.stream().anyMatch(row -> key(row).equals(key(result)));
        if (duplicated) {
            throw new IllegalStateException("duplicated sequence " + key(result));
        }
        rows.add(result);
    }

    @Override
    public List<EmissionResult> pending(String reference) {
        return rows.stream()
                .filter(row -> row.reference().equals(reference) && !published.contains(key(row)))
                .sorted(Comparator.comparingInt(EmissionResult::sequence))
                .toList();
    }

    @Override
    public void markPublished(String reference, int sequence) {
        published.add(reference + "#" + sequence);
    }

    public List<EmissionResult> all() {
        return List.copyOf(rows);
    }

    private static String key(EmissionResult result) {
        return result.reference() + "#" + result.sequence();
    }
}
