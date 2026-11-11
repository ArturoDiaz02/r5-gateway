package com.soat.gateway.application.port;

import com.soat.gateway.domain.emission.Emission;
import com.soat.gateway.domain.result.ResultHistory;

public record EmissionRecord(
        Emission emission,
        String correlationId,
        ResultHistory history,
        String policyNumber) {

    public String reference() {
        return emission.reference();
    }
}
