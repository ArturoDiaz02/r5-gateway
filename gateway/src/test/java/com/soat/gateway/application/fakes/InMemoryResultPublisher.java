package com.soat.gateway.application.fakes;

import com.soat.gateway.application.port.ResultPublisher;
import com.soat.gateway.domain.result.EmissionResult;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryResultPublisher implements ResultPublisher {

    private final InMemoryUnitOfWork unitOfWork;
    private final List<EmissionResult> published = new ArrayList<>();
    private final List<Boolean> insideTransaction = new ArrayList<>();
    private boolean failing;

    public InMemoryResultPublisher(InMemoryUnitOfWork unitOfWork) {
        this.unitOfWork = unitOfWork;
    }

    @Override
    public void publish(EmissionResult result) {
        if (failing) {
            throw new IllegalStateException("broker unavailable");
        }
        insideTransaction.add(unitOfWork.inTransaction());
        published.add(result);
    }

    public void failing(boolean failing) {
        this.failing = failing;
    }

    public List<EmissionResult> published() {
        return List.copyOf(published);
    }

    public List<Boolean> insideTransaction() {
        return List.copyOf(insideTransaction);
    }
}
