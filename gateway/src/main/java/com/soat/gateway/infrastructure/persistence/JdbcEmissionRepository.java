package com.soat.gateway.infrastructure.persistence;

import com.soat.gateway.application.port.EmissionRecord;
import com.soat.gateway.application.port.EmissionRepository;
import com.soat.gateway.domain.emission.Decision;
import com.soat.gateway.domain.emission.Emission;
import com.soat.gateway.domain.emission.EmissionStatus;
import com.soat.gateway.domain.result.ResultHistory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

public final class JdbcEmissionRepository implements EmissionRepository {

    private final JdbcClient jdbc;

    public JdbcEmissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(EmissionRecord record) {
        Emission emission = record.emission();
        return jdbc.sql("""
                        INSERT INTO emission (reference, correlation_id, status, quoted_premium_cents, requested_at,
                                              policy_number, last_sequence, last_provisional)
                        VALUES (:reference, :correlationId, :status, :premium, :requestedAt,
                                :policyNumber, :lastSequence, :lastProvisional)
                        ON CONFLICT (reference) DO NOTHING""")
                .param("reference", emission.reference())
                .param("correlationId", record.correlationId())
                .param("status", emission.status().name())
                .param("premium", emission.quotedPremiumCents())
                .param("requestedAt", OffsetDateTime.ofInstant(emission.requestedAt(), ZoneOffset.UTC))
                .param("policyNumber", record.policyNumber())
                .param("lastSequence", record.history().lastSequence())
                .param("lastProvisional", record.history().lastProvisional())
                .update() == 1;
    }

    @Override
    public Optional<EmissionRecord> find(String reference) {
        return jdbc.sql("""
                        SELECT reference, correlation_id, status, quoted_premium_cents, requested_at,
                               policy_number, last_sequence, last_provisional
                        FROM emission WHERE reference = :reference""")
                .param("reference", reference)
                .query(JdbcEmissionRepository::map)
                .optional();
    }

    @Override
    public boolean transition(String reference, Decision.Transition transition, ResultHistory history,
                              String policyNumber) {
        if (transition.allowedOrigins().isEmpty()) {
            return false;
        }
        return jdbc.sql("""
                        UPDATE emission
                           SET status = :target,
                               policy_number = COALESCE(:policyNumber, policy_number),
                               last_sequence = :lastSequence,
                               last_provisional = :lastProvisional,
                               version = version + 1,
                               updated_at = now()
                         WHERE reference = :reference
                           AND status IN (:origins)""")
                .param("target", transition.to().name())
                .param("policyNumber", policyNumber)
                .param("lastSequence", history.lastSequence())
                .param("lastProvisional", history.lastProvisional())
                .param("reference", reference)
                .param("origins", transition.allowedOrigins().stream().map(Enum::name).toList())
                .update() == 1;
    }

    private static EmissionRecord map(ResultSet row, int number) throws SQLException {
        Emission emission = new Emission(
                row.getString("reference"),
                EmissionStatus.valueOf(row.getString("status")),
                row.getLong("quoted_premium_cents"),
                row.getObject("requested_at", OffsetDateTime.class).toInstant());
        return new EmissionRecord(
                emission,
                row.getString("correlation_id"),
                new ResultHistory(row.getInt("last_sequence"), row.getBoolean("last_provisional")),
                row.getString("policy_number"));
    }
}
