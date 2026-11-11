package com.soat.gateway.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soat.gateway.application.port.EmissionRequest;
import com.soat.gateway.domain.result.EmissionResult;
import com.soat.gateway.domain.result.ResultReason;
import com.soat.gateway.domain.result.ResultType;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

import java.time.Duration;
import java.time.Instant;

import static com.soat.gateway.infrastructure.messaging.RabbitTestSupport.containing;
import static com.soat.gateway.infrastructure.messaging.RabbitTestSupport.receive;
import static org.assertj.core.api.Assertions.assertThat;

class RabbitSenderTest {

    private final MessageCodec codec = new MessageCodec();
    private final RabbitSender sender = new RabbitSender(RabbitTestSupport.RABBIT_TEMPLATE);
    private final RabbitRequestQueue queue = new RabbitRequestQueue(sender, codec);
    private final RabbitResultPublisher publisher = new RabbitResultPublisher(sender, codec);

    private static EmissionRequest request(String reference) {
        return new EmissionRequest(reference, "corr-1", Instant.parse("2026-11-09T14:00:00Z"), 89_130_000L, "ABC123");
    }

    @Test
    void retryArrivesAfterTheDelayWithTheNextAttempt() {
        queue.retry(request("SOAT-R1"), 2, Duration.ofSeconds(1));

        assertThat(receive("emission.requests", containing("SOAT-R1"), Duration.ofMillis(500))).isNull();
        Message message = receive("emission.requests", containing("SOAT-R1"), Duration.ofSeconds(5));

        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().<Integer>getHeader(MessageCodec.ATTEMPT_HEADER)).isEqualTo(2);
        assertThat(codec.readRequest(message.getBody())).isEqualTo(request("SOAT-R1"));
    }

    @Test
    void sendToDeadLetterLeavesTheRequestInTheDeadLetterQueue() {
        queue.sendToDeadLetter(request("SOAT-R2"));

        Message message = receive("emission.requests.dlq", containing("SOAT-R2"), Duration.ofSeconds(5));

        assertThat(message).isNotNull();
        assertThat(codec.readRequest(message.getBody())).isEqualTo(request("SOAT-R2"));
    }

    @Test
    void resultIsPublishedWithTheAgreedContract() throws Exception {
        publisher.publish(new EmissionResult("SOAT-R3", 2, ResultType.ERROR, ResultReason.EXPIRED, false,
                1, null, Instant.parse("2026-11-10T13:00:00.123Z"), "corr-3"));

        Message message = receive("emission.results", containing("SOAT-R3"), Duration.ofSeconds(5));

        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().<String>getHeader("correlation_id")).isEqualTo("corr-3");
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(message.getBody())).isEqualTo(mapper.readTree("""
                {"referencia":"SOAT-R3","secuencia":2,"resultado":"ERROR","motivo":"VENCIDA","definitivo":false,
                 "corrige_secuencia":1,"numero_poliza":null,"ocurrido_en":"2026-11-10T13:00:00.123Z",
                 "correlation_id":"corr-3"}"""));
    }

    @Test
    void successResultUsesTheContractValues() throws Exception {
        publisher.publish(new EmissionResult("SOAT-R4", 1, ResultType.SUCCESS, null, true,
                null, "POL-4", Instant.parse("2026-11-10T13:00:00Z"), "corr-4"));

        Message message = receive("emission.results", containing("SOAT-R4"), Duration.ofSeconds(5));

        assertThat(new ObjectMapper().readTree(message.getBody()).get("resultado").asText()).isEqualTo("EXITOSA");
    }

    @Test
    void rejectionResultUsesTheContractReason() throws Exception {
        publisher.publish(new EmissionResult("SOAT-R5", 1, ResultType.ERROR, ResultReason.REJECTED, true,
                null, null, Instant.parse("2026-11-10T13:00:00Z"), "corr-5"));

        Message message = receive("emission.results", containing("SOAT-R5"), Duration.ofSeconds(5));

        assertThat(new ObjectMapper().readTree(message.getBody()).get("motivo").asText()).isEqualTo("RECHAZADA");
    }
}
