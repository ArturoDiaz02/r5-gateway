package com.soat.simulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProviderSimulator {

    private static final Pattern REFERENCE = Pattern.compile("\"referencia\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern AMOUNT = Pattern.compile("\"monto_centavos\"\\s*:\\s*(\\d+)");
    private static final int MAX_WEBHOOK_DELIVERIES = 5;

    private record Emission(String reference, long amount, String status, AtomicInteger events) {

        Emission withStatus(String newStatus) {
            return new Emission(reference, amount, newStatus, events);
        }

        String policyNumber() {
            return "APROBADO".equals(status) ? "POL-" + reference : null;
        }
    }

    private final int requestedPort;
    private final String webhookUrl;
    private final Signer signer;
    private final Duration webhookDelay;
    private final Duration slowDelay;
    private final Duration webhookRetryDelay;
    private final Map<String, Emission> emissions = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private HttpServer server;

    public ProviderSimulator(int port, String webhookUrl, Signer signer, Duration webhookDelay,
                             Duration slowDelay, Duration webhookRetryDelay) {
        this.requestedPort = port;
        this.webhookUrl = webhookUrl;
        this.signer = signer;
        this.webhookDelay = webhookDelay;
        this.slowDelay = slowDelay;
        this.webhookRetryDelay = webhookRetryDelay;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(requestedPort), 0);
        server.createContext("/emisiones", this::emissions);
        server.createContext("/simulator/approve/", this::manualApproval);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public void stop() {
        server.stop(0);
        scheduler.shutdownNow();
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void emissions(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("POST".equals(exchange.getRequestMethod()) && path.equals("/emisiones")) {
            request(exchange);
        } else if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/emisiones/")) {
            status(exchange, path.substring("/emisiones/".length()));
        } else {
            respond(exchange, 405, "");
        }
    }

    private void request(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String reference = extract(REFERENCE, body);
        long amount = Long.parseLong(extract(AMOUNT, body));
        Behavior behavior = Behavior.of(reference);
        System.out.println("simulator: request " + reference + " behavior " + behavior);
        if (behavior == Behavior.SERVER_ERROR) {
            respond(exchange, 500, "");
            return;
        }
        if (behavior == Behavior.SLOW) {
            pause(slowDelay);
        }
        Emission emission = new Emission(reference, amount, initialStatus(behavior), new AtomicInteger());
        if (emissions.putIfAbsent(reference, emission) == null) {
            scheduleWebhooks(emission, behavior);
        }
        respond(exchange, 202, "");
    }

    private void status(HttpExchange exchange, String reference) throws IOException {
        Emission emission = emissions.get(reference);
        if (emission == null) {
            respond(exchange, 404, "");
            return;
        }
        respond(exchange, 200, "{\"referencia\":\"%s\",\"estado\":\"%s\",\"monto_centavos\":%d,\"numero_poliza\":%s}"
                .formatted(emission.reference(), emission.status(), emission.amount(), json(emission.policyNumber())));
    }

    private void manualApproval(HttpExchange exchange) throws IOException {
        String reference = exchange.getRequestURI().getPath().substring("/simulator/approve/".length());
        Emission approved = emissions.computeIfPresent(reference, (key, emission) -> emission.withStatus("APROBADO"));
        if (approved == null) {
            respond(exchange, 404, "");
            return;
        }
        String id = "evt_" + reference + "_" + approved.events().incrementAndGet();
        scheduler.execute(() -> deliverWebhook(webhookBody(id, approved)));
        respond(exchange, 202, "");
    }

    private static String initialStatus(Behavior behavior) {
        return switch (behavior) {
            case REJECT -> "RECHAZADO";
            case NEVER -> "PENDIENTE";
            default -> "APROBADO";
        };
    }

    private void scheduleWebhooks(Emission emission, Behavior behavior) {
        List<String> bodies = switch (behavior) {
            case OK, SLOW, REJECT -> List.of(webhookBody(nextId(emission), emission));
            case DUPLICATES -> {
                String approved = webhookBody(nextId(emission), emission);
                yield List.of(approved, approved, approved,
                        webhookBody(nextId(emission), emission.withStatus("PENDIENTE")));
            }
            default -> List.of();
        };
        if (!bodies.isEmpty()) {
            scheduler.schedule(() -> bodies.forEach(this::deliverWebhook),
                    webhookDelay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static String nextId(Emission emission) {
        return "evt_" + emission.reference() + "_" + emission.events().incrementAndGet();
    }

    private static String webhookBody(String id, Emission emission) {
        return ("{\"id\":\"%s\",\"tipo\":\"pago.actualizado\",\"creado_en\":\"%s\",\"data\":{\"referencia\":\"%s\","
                + "\"estado\":\"%s\",\"monto_centavos\":%d,\"numero_poliza\":%s}}")
                .formatted(id, Instant.now().truncatedTo(ChronoUnit.MILLIS), emission.reference(), emission.status(),
                        emission.amount(), json(emission.policyNumber()));
    }

    private void deliverWebhook(String body) {
        for (int delivery = 1; delivery <= MAX_WEBHOOK_DELIVERIES; delivery++) {
            long timestamp = Instant.now().getEpochSecond();
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(webhookUrl))
                                .timeout(Duration.ofSeconds(5))
                                .header("Content-Type", "application/json")
                                .header("X-Timestamp", String.valueOf(timestamp))
                                .header("X-Firma", signer.sign(timestamp, body))
                                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.discarding());
                System.out.println("simulator: webhook HTTP " + response.statusCode() + " " + body);
                if (response.statusCode() / 100 == 2) {
                    return;
                }
            } catch (IOException e) {
                System.out.println("simulator: webhook got no answer (" + e.getMessage() + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            pause(webhookRetryDelay);
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String extract(Pattern pattern, String body) {
        Matcher match = pattern.matcher(body);
        if (!match.find()) {
            throw new IllegalArgumentException("missing field: " + pattern.pattern());
        }
        return match.group(1);
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
