package com.soat.gateway.application.port;

import java.time.Duration;

public interface RequestQueue {

    void retry(EmissionRequest request, int attempt, Duration delay);

    void sendToDeadLetter(EmissionRequest request);
}
