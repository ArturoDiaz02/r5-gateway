package com.soat.gateway.application.fakes;

import com.soat.gateway.application.port.EmissionRequest;
import com.soat.gateway.application.port.RequestQueue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class InMemoryRequestQueue implements RequestQueue {

    public record Retry(EmissionRequest request, int attempt, Duration delay) {
    }

    private final List<Retry> retries = new ArrayList<>();
    private final List<EmissionRequest> deadLetter = new ArrayList<>();

    @Override
    public void retry(EmissionRequest request, int attempt, Duration delay) {
        retries.add(new Retry(request, attempt, delay));
    }

    @Override
    public void sendToDeadLetter(EmissionRequest request) {
        deadLetter.add(request);
    }

    public List<Retry> retries() {
        return List.copyOf(retries);
    }

    public List<EmissionRequest> deadLetter() {
        return List.copyOf(deadLetter);
    }
}
