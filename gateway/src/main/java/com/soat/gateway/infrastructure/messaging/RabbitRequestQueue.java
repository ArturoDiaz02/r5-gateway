package com.soat.gateway.infrastructure.messaging;

import com.soat.gateway.application.port.EmissionRequest;
import com.soat.gateway.application.port.RequestQueue;

import java.time.Duration;
import java.util.Map;

public final class RabbitRequestQueue implements RequestQueue {

    private final RabbitSender sender;
    private final MessageCodec codec;

    public RabbitRequestQueue(RabbitSender sender, MessageCodec codec) {
        this.sender = sender;
        this.codec = codec;
    }

    @Override
    public void retry(EmissionRequest request, int attempt, Duration delay) {
        sender.sendDelayed("requests", codec.write(request), delay, Map.of(MessageCodec.ATTEMPT_HEADER, attempt));
    }

    @Override
    public void sendToDeadLetter(EmissionRequest request) {
        sender.send("requests.dlq", codec.write(request), Map.of());
    }
}
