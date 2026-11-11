package com.soat.gateway.application.fakes;

import com.soat.gateway.application.port.FailedEmissionHandler;
import com.soat.gateway.domain.emission.ReviewReason;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryFailedEmissionHandler implements FailedEmissionHandler {

    private final List<String> notifications = new ArrayList<>();

    @Override
    public void reviewRequired(String reference) {
        notifications.add("reviewRequired " + reference);
    }

    @Override
    public void expired(String reference) {
        notifications.add("expired " + reference);
    }

    @Override
    public void manualReview(String reference, ReviewReason reason) {
        notifications.add("manualReview " + reference + " " + reason);
    }

    @Override
    public void providerConflict(String reference) {
        notifications.add("providerConflict " + reference);
    }

    public List<String> notifications() {
        return List.copyOf(notifications);
    }
}
