package com.soat.gateway.infrastructure.messaging;

import com.soat.gateway.application.port.EmissionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ConsumersRabbitTest {

    private static final Duration RETRY_DELAY = Duration.ofMillis(500);

    private final MessageCodec codec = new MessageCodec();
    private final RabbitSender sender = new RabbitSender(RabbitTestSupport.RABBIT_TEMPLATE);
    private final RabbitAdmin admin = new RabbitAdmin(RabbitTestSupport.CONNECTIONS);
    private final List<SimpleMessageListenerContainer> containers = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        containers.forEach(SimpleMessageListenerContainer::stop);
    }

    private void listen(String queue, MessageListener listener, int consumers) {
        admin.purgeQueue(queue, false);
        SimpleMessageListenerContainer container = Consumers.container(RabbitTestSupport.CONNECTIONS, queue, listener, consumers);
        containers.add(container);
        container.start();
    }

    private static EmissionRequest request(String reference) {
        return new EmissionRequest(reference, "corr-1", Instant.parse("2026-11-09T14:00:00Z"), 89_130_000L, "ABC123");
    }

    @Test
    void requestIsProcessedWithItsAttemptAndAcknowledged() {
        List<String> processed = new CopyOnWriteArrayList<>();
        listen("emission.requests", Consumers.requests(
                (r, attempt) -> processed.add(r.reference() + "#" + attempt), codec, sender, RETRY_DELAY), 2);

        new RabbitRequestQueue(sender, codec).retry(request("SOAT-K1"), 3, Duration.ZERO);

        await().atMost(Duration.ofSeconds(5)).until(() -> processed.contains("SOAT-K1#3"));
        await().atMost(Duration.ofSeconds(5))
                .until(() -> admin.getQueueInfo("emission.requests").getMessageCount() == 0);
    }

    @Test
    void requestWithoutAttemptHeaderIsTheFirstAttempt() {
        List<String> processed = new CopyOnWriteArrayList<>();
        listen("emission.requests", Consumers.requests(
                (r, attempt) -> processed.add(r.reference() + "#" + attempt), codec, sender, RETRY_DELAY), 1);

        sender.send("requests", codec.write(request("SOAT-K2")), Map.of());

        await().atMost(Duration.ofSeconds(5)).until(() -> processed.contains("SOAT-K2#1"));
    }

    @Test
    void unexpectedErrorDelaysTheSameMessageAgain() {
        AtomicInteger calls = new AtomicInteger();
        List<Instant> instants = new CopyOnWriteArrayList<>();
        listen("emission.status-checks", Consumers.statusChecks((reference, attempt) -> {
            instants.add(Instant.now());
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("database down");
            }
        }, codec, sender, RETRY_DELAY), 1);

        sender.sendDelayed("status-checks", codec.write(new MessageCodec.StatusCheckMessage("SOAT-K3", 2)),
                Duration.ZERO, Map.of());

        await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() == 2);
        assertThat(Duration.between(instants.get(0), instants.get(1))).isGreaterThanOrEqualTo(Duration.ofMillis(400));
    }

    @Test
    void expirationAndDeadLetterDeliverTheReference() {
        List<String> received = new CopyOnWriteArrayList<>();
        listen("emission.expirations", Consumers.expirations(
                reference -> received.add("e:" + reference), codec, sender, RETRY_DELAY), 1);
        listen("emission.requests.dlq", Consumers.deadLetter(
                reference -> received.add("dlq:" + reference), codec, sender, RETRY_DELAY), 1);

        sender.sendDelayed("expirations", codec.write(new MessageCodec.ExpirationMessage("SOAT-K4")),
                Duration.ZERO, Map.of());
        new RabbitRequestQueue(sender, codec).sendToDeadLetter(request("SOAT-K5"));

        await().atMost(Duration.ofSeconds(5)).until(() -> received.containsAll(List.of("e:SOAT-K4", "dlq:SOAT-K5")));
    }

    @Test
    void neverMoreThanTwentyMessagesInFlight() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        listen("emission.requests", Consumers.requests((r, attempt) -> {
            maximum.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            finished.incrementAndGet();
        }, codec, sender, RETRY_DELAY), 20);

        for (int i = 0; i < 60; i++) {
            sender.send("requests", codec.write(request("SOAT-L" + i)), Map.of());
        }

        await().atMost(Duration.ofSeconds(20)).until(() -> finished.get() == 60);
        assertThat(maximum.get()).isBetween(2, 20);
    }
}
