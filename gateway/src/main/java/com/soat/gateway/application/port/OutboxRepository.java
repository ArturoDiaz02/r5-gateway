package com.soat.gateway.application.port;

import com.soat.gateway.domain.result.EmissionResult;

import java.util.List;

public interface OutboxRepository {

    void add(EmissionResult result);

    List<EmissionResult> pending(String reference);

    void markPublished(String reference, int sequence);
}
