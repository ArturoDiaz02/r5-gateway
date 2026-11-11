package com.soat.gateway.domain.emission;

import java.util.Set;

public sealed interface Decision {

    record Transition(EmissionStatus from, EmissionStatus to, Set<EmissionStatus> allowedOrigins)
            implements Decision {
    }

    record Ignore(IgnoreReason reason) implements Decision {
    }

    record ManualReview(ReviewReason reason) implements Decision {
    }
}
