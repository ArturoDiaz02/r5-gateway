package com.soat.gateway.application.fakes;

import com.soat.gateway.application.port.EmissionProvider;
import com.soat.gateway.application.port.EmissionRequest;
import com.soat.gateway.application.port.ProviderFailure;
import com.soat.gateway.domain.emission.ProviderNotice;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class FakeEmissionProvider implements EmissionProvider {

    private final List<String> journal;
    private final InMemoryUnitOfWork unitOfWork;
    private final List<Boolean> requestsInsideTransaction = new ArrayList<>();
    private Runnable onRequest = () -> { };
    private boolean failRequests;
    private boolean failChecks;
    private ProviderNotice checkAnswer;
    private int checks;

    public FakeEmissionProvider(List<String> journal, InMemoryUnitOfWork unitOfWork) {
        this.journal = journal;
        this.unitOfWork = unitOfWork;
    }

    @Override
    public void requestEmission(EmissionRequest request) {
        journal.add("requestEmission " + request.reference());
        requestsInsideTransaction.add(unitOfWork.inTransaction());
        onRequest.run();
        if (failRequests) {
            throw new ProviderFailure("provider returned 503");
        }
    }

    @Override
    public Optional<ProviderNotice> checkStatus(String reference) {
        checks++;
        if (failChecks) {
            throw new ProviderFailure("provider timeout");
        }
        return Optional.ofNullable(checkAnswer);
    }

    public void onRequest(Runnable action) {
        this.onRequest = action;
    }

    public void failRequests(boolean fail) {
        this.failRequests = fail;
    }

    public void failChecks(boolean fail) {
        this.failChecks = fail;
    }

    public void answerChecksWith(ProviderNotice notice) {
        this.checkAnswer = notice;
    }

    public long requests() {
        return journal.stream().filter(line -> line.startsWith("requestEmission")).count();
    }

    public int checks() {
        return checks;
    }

    public List<Boolean> requestsInsideTransaction() {
        return List.copyOf(requestsInsideTransaction);
    }
}
