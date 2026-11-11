package com.soat.gateway.application.port;

import com.soat.gateway.domain.emission.ReviewReason;

public interface FailedEmissionHandler {

    void reviewRequired(String reference);

    void expired(String reference);

    void manualReview(String reference, ReviewReason reason);

    void providerConflict(String reference);
}
