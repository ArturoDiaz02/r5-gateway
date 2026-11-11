package com.soat.gateway.infrastructure.messaging;

import com.soat.gateway.application.port.ResultPublisher;
import com.soat.gateway.domain.result.EmissionResult;

import java.util.Map;

public final class RabbitResultPublisher implements ResultPublisher {

    private final RabbitSender sender;
    private final MessageCodec codec;

    public RabbitResultPublisher(RabbitSender sender, MessageCodec codec) {
        this.sender = sender;
        this.codec = codec;
    }

    @Override
    public void publish(EmissionResult result) {
        sender.send("results", codec.write(result), Map.of("correlation_id", result.correlationId()));
    }
}
