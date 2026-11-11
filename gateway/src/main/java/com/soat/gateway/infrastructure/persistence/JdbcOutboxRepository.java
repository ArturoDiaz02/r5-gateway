package com.soat.gateway.infrastructure.persistence;

import com.soat.gateway.application.port.ConcurrencyConflict;
import com.soat.gateway.application.port.OutboxRepository;
import com.soat.gateway.domain.result.EmissionResult;
import com.soat.gateway.domain.result.ResultReason;
import com.soat.gateway.domain.result.ResultType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class JdbcOutboxRepository implements OutboxRepository {

    private final JdbcClient jdbc;

    public JdbcOutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(EmissionResult result) {
        try {
            jdbc.sql("""
                            INSERT INTO outbox (reference, sequence, result_type, reason, definitive, corrects_sequence,
                                                policy_number, occurred_at, correlation_id)
                            VALUES (:reference, :sequence, :type, :reason, :definitive, :correctsSequence,
                                    :policyNumber, :occurredAt, :correlationId)""")
                    .param("reference", result.reference())
                    .param("sequence", result.sequence())
                    .param("type", result.type().name())
                    .param("reason", result.reason() != null ? result.reason().name() : null)
                    .param("definitive", result.definitive())
                    .param("correctsSequence", result.correctsSequence())
                    .param("policyNumber", result.policyNumber())
                    .param("occurredAt", OffsetDateTime.ofInstant(result.occurredAt(), ZoneOffset.UTC))
                    .param("correlationId", result.correlationId())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConcurrencyConflict("sequence already registered for " + result.reference(), e);
        }
    }

    @Override
    public List<EmissionResult> pending(String reference) {
        return jdbc.sql("""
                        SELECT reference, sequence, result_type, reason, definitive, corrects_sequence,
                               policy_number, occurred_at, correlation_id
                        FROM outbox
                        WHERE reference = :reference AND published_at IS NULL
                        ORDER BY sequence""")
                .param("reference", reference)
                .query(JdbcOutboxRepository::map)
                .list();
    }

    @Override
    public void markPublished(String reference, int sequence) {
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE reference = :reference AND sequence = :sequence")
                .param("reference", reference)
                .param("sequence", sequence)
                .update();
    }

    private static EmissionResult map(ResultSet row, int number) throws SQLException {
        String reason = row.getString("reason");
        return new EmissionResult(
                row.getString("reference"),
                row.getInt("sequence"),
                ResultType.valueOf(row.getString("result_type")),
                reason != null ? ResultReason.valueOf(reason) : null,
                row.getBoolean("definitive"),
                row.getObject("corrects_sequence", Integer.class),
                row.getString("policy_number"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                row.getString("correlation_id"));
    }
}
