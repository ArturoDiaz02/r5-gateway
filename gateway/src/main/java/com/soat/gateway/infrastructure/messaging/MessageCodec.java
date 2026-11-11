package com.soat.gateway.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.soat.gateway.application.port.EmissionRequest;
import com.soat.gateway.domain.result.EmissionResult;
import com.soat.gateway.domain.result.ResultReason;
import com.soat.gateway.domain.result.ResultType;

import java.io.IOException;
import java.time.Instant;

public final class MessageCodec {

    public static final String ATTEMPT_HEADER = "x-attempt";

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public record RequestMessage(
            @JsonProperty("referencia") String reference,
            @JsonProperty("correlation_id") String correlationId,
            @JsonProperty("solicitado_en") Instant requestedAt,
            @JsonProperty("prima_cotizada_centavos") long quotedPremiumCents,
            @JsonProperty("placa") String plate) {
    }

    public record StatusCheckMessage(
            @JsonProperty("reference") String reference,
            @JsonProperty("attempt") int attempt) {
    }

    public record ExpirationMessage(@JsonProperty("reference") String reference) {
    }

    public record ResultMessage(
            @JsonProperty("referencia") String reference,
            @JsonProperty("secuencia") int sequence,
            @JsonProperty("resultado") String result,
            @JsonProperty("motivo") String reason,
            @JsonProperty("definitivo") boolean definitive,
            @JsonProperty("corrige_secuencia") Integer correctsSequence,
            @JsonProperty("numero_poliza") String policyNumber,
            @JsonProperty("ocurrido_en") Instant occurredAt,
            @JsonProperty("correlation_id") String correlationId) {
    }

    public byte[] write(EmissionRequest request) {
        return write((Object) new RequestMessage(request.reference(), request.correlationId(),
                request.requestedAt(), request.quotedPremiumCents(), request.plate()));
    }

    public byte[] write(EmissionResult result) {
        return write((Object) new ResultMessage(result.reference(), result.sequence(), wire(result.type()),
                wire(result.reason()), result.definitive(), result.correctsSequence(), result.policyNumber(),
                result.occurredAt(), result.correlationId()));
    }

    public byte[] write(Object message) {
        try {
            return mapper.writeValueAsBytes(message);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public EmissionRequest readRequest(byte[] body) {
        RequestMessage message = read(body, RequestMessage.class);
        return new EmissionRequest(message.reference(), message.correlationId(), message.requestedAt(),
                message.quotedPremiumCents(), message.plate());
    }

    public StatusCheckMessage readStatusCheck(byte[] body) {
        return read(body, StatusCheckMessage.class);
    }

    public String readExpiration(byte[] body) {
        return read(body, ExpirationMessage.class).reference();
    }

    private static String wire(ResultType type) {
        return type == ResultType.SUCCESS ? "EXITOSA" : "ERROR";
    }

    private static String wire(ResultReason reason) {
        if (reason == null) {
            return null;
        }
        return reason == ResultReason.REJECTED ? "RECHAZADA" : "VENCIDA";
    }

    private <T> T read(byte[] body, Class<T> type) {
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new InvalidMessage("invalid message", e);
        }
    }
}
