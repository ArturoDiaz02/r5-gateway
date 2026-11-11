package com.soat.gateway.domain.security;

public enum VerificationResult {
    VALID,
    INVALID_SIGNATURE,
    OUTSIDE_WINDOW,
    MALFORMED
}
