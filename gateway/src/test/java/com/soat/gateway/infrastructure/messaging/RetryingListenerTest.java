package com.soat.gateway.infrastructure.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RetryingListenerTest {

    private final RabbitSender sender = mock(RabbitSender.class);
    private final MessageCodec codec = new MessageCodec();

    private static Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    @Test
    void unreadableMessageIsDiscardedWithoutRetry() {
        Consumers.statusChecks((reference, attempt) -> { }, codec, sender, Duration.ofSeconds(2))
                .onMessage(message("verification"));

        verify(sender, never()).sendDelayed(any(), any(), any(), any());
    }

    @Test
    void processingErrorIsDelayedAgain() {
        Consumers.statusChecks((reference, attempt) -> {
                    throw new IllegalStateException("database down");
                }, codec, sender, Duration.ofSeconds(2))
                .onMessage(message("{\"reference\":\"SOAT-1\",\"attempt\":1}"));

        verify(sender).sendDelayed(eq("status-checks"), any(), eq(Duration.ofSeconds(2)), eq(Map.of()));
    }
}
