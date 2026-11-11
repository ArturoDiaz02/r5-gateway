package com.soat.gateway.domain.emission;

public enum IgnoreReason {
    STALE_NOTICE,
    PENDING_HAS_NO_EFFECT,
    PROVIDER_CONFLICT,
    UNKNOWN_PROVIDER_STATUS,
    PREMATURE_EXPIRATION
}
