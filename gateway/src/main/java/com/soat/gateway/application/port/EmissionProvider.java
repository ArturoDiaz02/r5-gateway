package com.soat.gateway.application.port;

import com.soat.gateway.domain.emission.ProviderNotice;

import java.util.Optional;

public interface EmissionProvider {

    void requestEmission(EmissionRequest request);

    Optional<ProviderNotice> checkStatus(String reference);
}
