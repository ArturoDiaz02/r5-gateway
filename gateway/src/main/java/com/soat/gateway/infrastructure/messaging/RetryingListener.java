package com.soat.gateway.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

final class RetryingListener implements MessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(RetryingListener.class);

    private final String routingKey;
    private final Consumer<Message> handler;
    private final RabbitSender sender;
    private final Duration retryDelay;

    RetryingListener(String routingKey, Consumer<Message> handler, RabbitSender sender, Duration retryDelay) {
        this.routingKey = routingKey;
        this.handler = handler;
        this.sender = sender;
        this.retryDelay = retryDelay;
    }

    @Override
    public void onMessage(Message message) {
        try {
            handler.accept(message);
        } catch (InvalidMessage e) {
            LOG.warn("message discarded on {}: {}", routingKey, e.getMessage());
        } catch (RuntimeException e) {
            Map<String, Object> headers = new HashMap<>(message.getMessageProperties().getHeaders());
            headers.remove("x-delay");
            sender.sendDelayed(routingKey, message.getBody(), retryDelay, headers);
        }
    }
}
