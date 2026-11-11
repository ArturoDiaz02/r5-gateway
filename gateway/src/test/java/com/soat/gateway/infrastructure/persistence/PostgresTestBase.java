package com.soat.gateway.infrastructure.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

public abstract class PostgresTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final HikariDataSource DATA_SOURCE;

    static {
        POSTGRES.start();
        DATA_SOURCE = new HikariDataSource();
        DATA_SOURCE.setJdbcUrl(POSTGRES.getJdbcUrl());
        DATA_SOURCE.setUsername(POSTGRES.getUsername());
        DATA_SOURCE.setPassword(POSTGRES.getPassword());
        DATA_SOURCE.setMaximumPoolSize(10);
        Flyway.configure().dataSource(DATA_SOURCE).load().migrate();
    }

    protected final JdbcClient jdbc = JdbcClient.create(DATA_SOURCE);
    protected final JdbcUnitOfWork unitOfWork =
            new JdbcUnitOfWork(new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE)));

    public static PostgreSQLContainer<?> container() {
        return POSTGRES;
    }

    @BeforeEach
    void cleanTables() {
        jdbc.sql("TRUNCATE emission, provider_event, outbox, scheduled_task").update();
    }
}
