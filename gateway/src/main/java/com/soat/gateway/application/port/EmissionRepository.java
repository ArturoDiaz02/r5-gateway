package com.soat.gateway.application.port;

import com.soat.gateway.domain.emission.Decision;
import com.soat.gateway.domain.result.ResultHistory;

import java.util.Optional;

public interface EmissionRepository {

    boolean insertIfAbsent(EmissionRecord record);

    Optional<EmissionRecord> find(String reference);

    boolean transition(String reference, Decision.Transition transition, ResultHistory history, String policyNumber);
}
