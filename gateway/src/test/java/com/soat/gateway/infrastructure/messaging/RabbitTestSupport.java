package com.soat.gateway.infrastructure.messaging;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.function.Predicate;

public final class RabbitTestSupport {

    public static final GenericContainer<?> RABBIT = new GenericContainer<>(
            new ImageFromDockerfile("soat-rabbitmq-test", false).withFileFromPath(".", Path.of("../local/rabbitmq")))
            .withEnv("RABBITMQ_DEFAULT_USER", "soat")
            .withEnv("RABBITMQ_DEFAULT_PASS", "soat")
            .withExposedPorts(5672, 15672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1).withStartupTimeout(Duration.ofMinutes(3)));

    public static final CachingConnectionFactory CONNECTIONS;
    public static final RabbitTemplate RABBIT_TEMPLATE;

    static {
        RABBIT.start();
        loadDefinitions();
        CONNECTIONS = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getMappedPort(5672));
        CONNECTIONS.setUsername("soat");
        CONNECTIONS.setPassword("soat");
        CONNECTIONS.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        RABBIT_TEMPLATE = new RabbitTemplate(CONNECTIONS);
    }

    private RabbitTestSupport() {
    }

    public static Message receive(String queue, Predicate<Message> matches, Duration limit) {
        long end = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < end) {
            Message message = RABBIT_TEMPLATE.receive(queue, 200);
            if (message != null && matches.test(message)) {
                return message;
            }
        }
        return null;
    }

    public static Predicate<Message> containing(String text) {
        return message -> new String(message.getBody(), StandardCharsets.UTF_8).contains(text);
    }

    private static void loadDefinitions() {
        try {
            String credentials = Base64.getEncoder().encodeToString("soat:soat".getBytes(StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://" + RABBIT.getHost() + ":" + RABBIT.getMappedPort(15672) + "/api/definitions"))
                    .header("Authorization", "Basic " + credentials)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofFile(Path.of("../local/rabbitmq/definitions.json")))
                    .build();
            HttpResponse<String> response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("definitions were not loaded: " + response.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
