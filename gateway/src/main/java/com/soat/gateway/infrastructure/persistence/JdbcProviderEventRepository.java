package com.soat.gateway.infrastructure.persistence;

import com.soat.gateway.application.port.ProviderEventRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcProviderEventRepository implements ProviderEventRepository {

    private final JdbcClient jdbc;

    public JdbcProviderEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean registerIfNew(String eventId, String reference) {
        return jdbc.sql("""
                        INSERT INTO provider_event (event_id, reference)
                        VALUES (:eventId, :reference)
                        ON CONFLICT (event_id) DO NOTHING""")
                .param("eventId", eventId)
                .param("reference", reference)
                .update() == 1;
    }
}
