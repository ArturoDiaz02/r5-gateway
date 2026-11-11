package com.soat.gateway.infrastructure.messaging;

import com.soat.gateway.infrastructure.persistence.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static com.soat.gateway.infrastructure.messaging.RabbitTestSupport.containing;
import static com.soat.gateway.infrastructure.messaging.RabbitTestSupport.receive;
import static org.assertj.core.api.Assertions.assertThat;

class RabbitDelayedTaskSchedulerTest extends PostgresTestBase {

    private final MessageCodec codec = new MessageCodec();
    private final RabbitDelayedTaskScheduler scheduler = new RabbitDelayedTaskScheduler(
            jdbc, new RabbitSender(RabbitTestSupport.RABBIT_TEMPLATE), codec, Clock.systemUTC());

    @Test
    void delayedStatusCheckArrivesAfterItsDelay() {
        scheduler.scheduleStatusCheck("SOAT-P1", 2, Duration.ofSeconds(1));

        assertThat(receive("emission.status-checks", containing("SOAT-P1"), Duration.ofMillis(500))).isNull();
        Message message = receive("emission.status-checks", containing("SOAT-P1"), Duration.ofSeconds(5));

        assertThat(message).isNotNull();
        assertThat(codec.readStatusCheck(message.getBody())).isEqualTo(new MessageCodec.StatusCheckMessage("SOAT-P1", 2));
    }

    @Test
    void duplicatedTaskIsPublishedOnlyOnce() {
        assertThat(scheduler.scheduleStatusCheck("SOAT-P2", 1, Duration.ZERO)).isTrue();
        assertThat(scheduler.scheduleStatusCheck("SOAT-P2", 1, Duration.ZERO)).isFalse();

        assertThat(receive("emission.status-checks", containing("SOAT-P2"), Duration.ofSeconds(3))).isNotNull();
        assertThat(receive("emission.status-checks", containing("SOAT-P2"), Duration.ofSeconds(1))).isNull();
    }

    @Test
    void expirationIsDelayedUntilTheRequestedInstant() {
        scheduler.scheduleExpiration("SOAT-P3", Instant.now().plusSeconds(1));

        assertThat(receive("emission.expirations", containing("SOAT-P3"), Duration.ofMillis(500))).isNull();
        Message message = receive("emission.expirations", containing("SOAT-P3"), Duration.ofSeconds(5));

        assertThat(message).isNotNull();
        assertThat(codec.readExpiration(message.getBody())).isEqualTo("SOAT-P3");
    }

    @Test
    void expirationInThePastIsDeliveredImmediately() {
        scheduler.scheduleExpiration("SOAT-P4", Instant.now().minusSeconds(60));

        assertThat(receive("emission.expirations", containing("SOAT-P4"), Duration.ofSeconds(3))).isNotNull();
    }
}
