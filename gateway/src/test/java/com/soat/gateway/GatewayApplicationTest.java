package com.soat.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.soat.gateway.infrastructure.messaging.RabbitTestSupport;
import com.soat.gateway.infrastructure.persistence.PostgresTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class GatewayApplicationTest {

    private static final WireMockServer PROVIDER = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        PROVIDER.start();
    }

    @Autowired
    private TestRestTemplate http;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PostgresTestBase.container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> PostgresTestBase.container().getUsername());
        registry.add("spring.datasource.password", () -> PostgresTestBase.container().getPassword());
        registry.add("spring.rabbitmq.host", RabbitTestSupport.RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RabbitTestSupport.RABBIT.getMappedPort(5672));
        registry.add("gateway.provider.url", PROVIDER::baseUrl);
        registry.add("gateway.status-checks", () -> "1s,1s,1s,1s,1s");
    }

    @AfterAll
    static void stop() {
        PROVIDER.stop();
    }

    @Test
    void startsAndReportsHealthy() {
        assertThat(http.getForEntity("/actuator/health", String.class).getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void unsignedWebhookIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        assertThat(http.postForEntity("/webhooks/pagos", new HttpEntity<>("{}", headers), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void publishedRequestEndsInASuccessfulResultThroughAStatusCheck() {
        PROVIDER.stubFor(post("/emisiones").willReturn(aResponse().withStatus(202)));
        PROVIDER.stubFor(get("/emisiones/SOAT-APP-1").willReturn(okJson("""
                {"referencia":"SOAT-APP-1","estado":"APROBADO","monto_centavos":89130000,"numero_poliza":"POL-APP-1"}""")));
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        String request = """
                {"referencia":"SOAT-APP-1","correlation_id":"corr-app","solicitado_en":"%s",
                 "prima_cotizada_centavos":89130000,"placa":"ABC123"}""".formatted(Instant.now());

        RabbitTestSupport.RABBIT_TEMPLATE.send("emission", "requests",
                new Message(request.getBytes(StandardCharsets.UTF_8), properties));

        Message result = RabbitTestSupport.receive("emission.results",
                RabbitTestSupport.containing("SOAT-APP-1"), Duration.ofSeconds(20));
        assertThat(result).isNotNull();
        assertThat(new String(result.getBody(), StandardCharsets.UTF_8))
                .contains("\"resultado\":\"EXITOSA\"", "\"numero_poliza\":\"POL-APP-1\"", "\"correlation_id\":\"corr-app\"");
    }
}
