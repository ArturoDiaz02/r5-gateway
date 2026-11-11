package com.soat.gateway.application.fakes;

import com.soat.gateway.application.port.ProviderEventRepository;

import java.util.HashSet;
import java.util.Set;

public final class InMemoryProviderEventRepository implements ProviderEventRepository {

    private final Set<String> events = new HashSet<>();

    @Override
    public boolean registerIfNew(String eventId, String reference) {
        return events.add(eventId);
    }
}
