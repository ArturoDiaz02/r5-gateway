package com.soat.gateway.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationsTest extends PostgresTestBase {

    @Test
    void migrationsAreAppliedAndValid() {
        Flyway flyway = Flyway.configure().dataSource(DATA_SOURCE).load();

        assertThat(flyway.info().applied()).hasSize(4);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void invalidStatusIsRejectedByTheDatabase() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO emission (reference, correlation_id, status, quoted_premium_cents, requested_at)
                VALUES ('SOAT-1', 'c', 'MADE_UP', 1, now())""").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void outboxSequenceIsUniquePerReference() {
        String insert = """
                INSERT INTO outbox (reference, sequence, result_type, definitive, occurred_at, correlation_id)
                VALUES ('SOAT-1', 1, 'SUCCESS', true, now(), 'c')""";
        jdbc.sql(insert).update();

        assertThatThrownBy(() -> jdbc.sql(insert).update()).isInstanceOf(DataIntegrityViolationException.class);
    }
}
