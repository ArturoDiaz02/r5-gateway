package com.soat.simulator;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderSimulatorTest {

    private static final String SECRET = "secreto-simulador";

    record Webhook(String timestamp, String signature, String body) {
    }

    private final List<Webhook> received = new CopyOnWriteArrayList<>();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private HttpServer gateway;
    private ProviderSimulator simulator;
    private int gatewayStatus = 200;

    @BeforeEach
    void start() throws IOException {
        gateway = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        gateway.createContext("/webhooks/pagos", exchange -> {
            received.add(new Webhook(
                    exchange.getRequestHeaders().getFirst("X-Timestamp"),
                    exchange.getRequestHeaders().getFirst("X-Firma"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(gatewayStatus, -1);
            exchange.close();
        });
        gateway.start();
        simulator = new ProviderSimulator(0,
                "http://localhost:" + gateway.getAddress().getPort() + "/webhooks/pagos",
                new Signer(SECRET), Duration.ofMillis(100), Duration.ofMillis(300), Duration.ofMillis(100));
        simulator.start();
    }

    @AfterEach
    void stop() {
        simulator.stop();
        gateway.stop(0);
    }

    private HttpResponse<String> issue(String reference) throws Exception {
        String body = "{\"referencia\":\"%s\",\"monto_centavos\":89130000,\"placa\":\"ABC123\"}".formatted(reference);
        return http.send(HttpRequest.newBuilder(URI.create(simulator.url() + "/emisiones"))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", reference)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> check(String reference) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(simulator.url() + "/emisiones/" + reference)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void waitUntil(Supplier<Boolean> condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.get() && System.nanoTime() < end) {
            Thread.sleep(50);
        }
        assertThat(condition.get()).isTrue();
    }

    @Test
    void approvalSendsASignedWebhookWithAmountAndPolicy() throws Exception {
        assertThat(issue("SOAT-1-OK").statusCode()).isEqualTo(202);

        waitUntil(() -> received.size() == 1);
        Webhook webhook = received.get(0);
        assertThat(webhook.body()).contains("\"referencia\":\"SOAT-1-OK\"", "\"estado\":\"APROBADO\"",
                "\"monto_centavos\":89130000", "\"numero_poliza\":\"POL-SOAT-1-OK\"", "\"tipo\":\"pago.actualizado\"");
        assertThat(webhook.signature())
                .isEqualTo(new Signer(SECRET).sign(Long.parseLong(webhook.timestamp()), webhook.body()));
        assertThat(check("SOAT-1-OK").body()).contains("\"estado\":\"APROBADO\"", "\"numero_poliza\":\"POL-SOAT-1-OK\"");
    }

    @Test
    void rejectionSendsARejectedWebhook() throws Exception {
        issue("SOAT-2-REJECT");

        waitUntil(() -> received.size() == 1);
        assertThat(received.get(0).body()).contains("\"estado\":\"RECHAZADO\"");
        assertThat(check("SOAT-2-REJECT").body()).contains("\"estado\":\"RECHAZADO\"");
    }

    @Test
    void noWebhookIsOnlyVisibleThroughAStatusCheck() throws Exception {
        issue("SOAT-3-NOWEBHOOK");

        Thread.sleep(400);
        assertThat(received).isEmpty();
        assertThat(check("SOAT-3-NOWEBHOOK").body()).contains("\"estado\":\"APROBADO\"");
    }

    @Test
    void duplicatesSendTheSameIdThreeTimesAndThenAPending() throws Exception {
        issue("SOAT-4-DUP");

        waitUntil(() -> received.size() == 4);
        List<String> bodies = received.stream().map(Webhook::body).toList();
        assertThat(bodies.subList(0, 3)).allMatch(b -> b.contains("\"id\":\"evt_SOAT-4-DUP_1\"") && b.contains("APROBADO"));
        assertThat(bodies.get(3)).contains("\"id\":\"evt_SOAT-4-DUP_2\"", "\"estado\":\"PENDIENTE\"");
    }

    @Test
    void serverErrorAnswers500() throws Exception {
        assertThat(issue("SOAT-5-5XX").statusCode()).isEqualTo(500);
    }

    @Test
    void slowTakesTimeToAnswer() throws Exception {
        long start = System.nanoTime();

        assertThat(issue("SOAT-6-SLOW").statusCode()).isEqualTo(202);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void neverApprovesNorNotifies() throws Exception {
        issue("SOAT-7-NEVER");

        Thread.sleep(400);
        assertThat(received).isEmpty();
        assertThat(check("SOAT-7-NEVER").body()).contains("\"estado\":\"PENDIENTE\"");
    }

    @Test
    void statusCheckOfAnUnknownReferenceIs404() throws Exception {
        assertThat(check("SOAT-MISSING").statusCode()).isEqualTo(404);
    }

    @Test
    void manualApprovalSendsAnApprovedWebhook() throws Exception {
        issue("SOAT-8-NEVER");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create(simulator.url() + "/simulator/approve/SOAT-8-NEVER"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(202);
        waitUntil(() -> received.size() == 1);
        assertThat(received.get(0).body()).contains("\"estado\":\"APROBADO\"");
        assertThat(check("SOAT-8-NEVER").body()).contains("\"estado\":\"APROBADO\"");
    }

    @Test
    void retriesTheWebhookWithAFreshTimestampWhenTheGatewayFails() throws Exception {
        gatewayStatus = 503;
        issue("SOAT-9-OK");

        waitUntil(() -> received.size() >= 2);
        gatewayStatus = 200;
        assertThat(received.get(0).body()).isEqualTo(received.get(1).body());
    }
}
