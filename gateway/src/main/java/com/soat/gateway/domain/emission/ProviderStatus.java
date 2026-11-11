package com.soat.gateway.domain.emission;

public enum ProviderStatus {
    APPROVED,
    PENDING,
    REJECTED,
    UNKNOWN;

    public static ProviderStatus fromWire(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return switch (value) {
            case "APROBADO" -> APPROVED;
            case "PENDIENTE" -> PENDING;
            case "RECHAZADO" -> REJECTED;
            default -> UNKNOWN;
        };
    }
}
