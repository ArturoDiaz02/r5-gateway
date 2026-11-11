package com.soat.gateway.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soat.gateway.application.port.ConcurrencyConflict;
import com.soat.gateway.application.service.RegisterNotice;
import com.soat.gateway.domain.emission.ProviderNotice;
import com.soat.gateway.domain.emission.ProviderStatus;
import com.soat.gateway.domain.security.SignatureVerifier;
import com.soat.gateway.domain.security.VerificationResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class PaymentWebhookController {

    private final SignatureVerifier verifier;
    private final RegisterNotice registerNotice;
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PaymentWebhookController(SignatureVerifier verifier, RegisterNotice registerNotice) {
        this.verifier = verifier;
        this.registerNotice = registerNotice;
    }

    @PostMapping(path = "/webhooks/pagos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Timestamp", required = false) String timestamp,
                                        @RequestHeader(value = "X-Firma", required = false) String signature,
                                        @RequestBody byte[] body) {
        if (verifier.verify(timestamp, signature, body) != VerificationResult.VALID) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        NoticeJson notice;
        try {
            notice = mapper.readValue(body, NoticeJson.class);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
        if (notice.id() == null || notice.data() == null || notice.data().reference() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            registerNotice.register(notice.id(), notice.data().reference(), new ProviderNotice(
                    ProviderStatus.fromWire(notice.data().status()),
                    notice.data().amountCents(),
                    notice.data().policyNumber()));
        } catch (ConcurrencyConflict e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok().build();
    }

    public record NoticeJson(
            @JsonProperty("id") String id,
            @JsonProperty("data") DataJson data) {
    }

    public record DataJson(
            @JsonProperty("referencia") String reference,
            @JsonProperty("estado") String status,
            @JsonProperty("monto_centavos") Long amountCents,
            @JsonProperty("numero_poliza") String policyNumber) {
    }
}
