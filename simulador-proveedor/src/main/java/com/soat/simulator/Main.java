package com.soat.simulator;

import java.io.IOException;
import java.time.Duration;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(variable("PORT", "8090"));
        ProviderSimulator simulator = new ProviderSimulator(
                port,
                variable("WEBHOOK_URL", "http://localhost:8080/webhooks/pagos"),
                new Signer(variable("HMAC_SECRET", "secreto-local-v1")),
                Duration.ofMillis(Long.parseLong(variable("WEBHOOK_DELAY_MS", "2000"))),
                Duration.ofMillis(Long.parseLong(variable("SLOW_DELAY_MS", "8000"))),
                Duration.ofMillis(Long.parseLong(variable("WEBHOOK_RETRY_DELAY_MS", "2000"))));
        simulator.start();
        System.out.println("simulator: listening on port " + port);
    }

    private static String variable(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
