package com.soat.gateway.infrastructure.persistence;

import com.soat.gateway.application.port.UnitOfWork;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

public final class JdbcUnitOfWork implements UnitOfWork {

    private final TransactionTemplate transactions;

    public JdbcUnitOfWork(TransactionTemplate transactions) {
        this.transactions = transactions;
    }

    @Override
    public <T> T execute(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }
}
