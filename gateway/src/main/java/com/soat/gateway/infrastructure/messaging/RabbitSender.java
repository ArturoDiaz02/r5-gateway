package com.soat.gateway.infrastructure.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.Map;

public final class RabbitSender {

    static final String EXCHANGE = "emission";
    static final String DELAYED_EXCHANGE = "emission.delayed";
    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitTemplate rabbit;

    public RabbitSender(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public void send(String routingKey, byte[] body, Map<String, Object> headers) {
        publish(EXCHANGE, routingKey, message(body, headers));
    }

    public void sendDelayed(String routingKey, byte[] body, Duration delay, Map<String, Object> headers) {
        Message message = message(body, headers);
        message.getMessageProperties().setHeader("x-delay", delay.toMillis());
        publish(DELAYED_EXCHANGE, routingKey, message);
    }

    private void publish(String exchange, String routingKey, Message message) {
        rabbit.invoke(operations -> {
            operations.send(exchange, routingKey, message);
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
    }

    private static Message message(byte[] body, Map<String, Object> headers) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        headers.forEach(properties::setHeader);
        return new Message(body, properties);
    }
}
