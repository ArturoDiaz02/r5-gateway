package com.soat.gateway.application.port;

import com.soat.gateway.domain.result.EmissionResult;

public interface ResultPublisher {

    void publish(EmissionResult result);
}
