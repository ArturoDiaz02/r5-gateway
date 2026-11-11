package com.soat.gateway.application.fakes;

import com.soat.gateway.application.port.UnitOfWork;

import java.util.function.Supplier;

public final class InMemoryUnitOfWork implements UnitOfWork {

    private int depth;

    @Override
    public <T> T execute(Supplier<T> work) {
        depth++;
        try {
            return work.get();
        } finally {
            depth--;
        }
    }

    public boolean inTransaction() {
        return depth > 0;
    }
}
