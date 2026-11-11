package com.soat.gateway.infrastructure.messaging;

import com.soat.gateway.application.port.DelayedTaskScheduler;
import com.soat.gateway.domain.sla.TaskName;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

public final class RabbitDelayedTaskScheduler implements DelayedTaskScheduler {

    private final JdbcClient jdbc;
    private final RabbitSender sender;
    private final MessageCodec codec;
    private final Clock clock;

    public RabbitDelayedTaskScheduler(JdbcClient jdbc, RabbitSender sender, MessageCodec codec, Clock clock) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.codec = codec;
        this.clock = clock;
    }

    @Override
    public boolean scheduleStatusCheck(String reference, int attempt, Duration delay) {
        return schedule(TaskName.statusCheck(reference, attempt), reference, delay, "status-checks",
                codec.write(new MessageCodec.StatusCheckMessage(reference, attempt)));
    }

    @Override
    public boolean scheduleExpiration(String reference, Instant at) {
        Duration delay = Duration.between(clock.instant(), at);
        return schedule(TaskName.expiration(reference), reference,
                delay.isNegative() ? Duration.ZERO : delay, "expirations",
                codec.write(new MessageCodec.ExpirationMessage(reference)));
    }

    private boolean schedule(String taskName, String reference, Duration delay, String routingKey, byte[] body) {
        int registered = jdbc.sql("""
                        INSERT INTO scheduled_task (task_name, reference, scheduled_for)
                        VALUES (:taskName, :reference, :scheduledFor)
                        ON CONFLICT (task_name) DO NOTHING""")
                .param("taskName", taskName)
                .param("reference", reference)
                .param("scheduledFor", OffsetDateTime.ofInstant(clock.instant().plus(delay), ZoneOffset.UTC))
                .update();
        if (registered == 0) {
            return false;
        }
        try {
            sender.sendDelayed(routingKey, body, delay, Map.of());
        } catch (RuntimeException e) {
            jdbc.sql("DELETE FROM scheduled_task WHERE task_name = :taskName").param("taskName", taskName).update();
            throw e;
        }
        return true;
    }
}
