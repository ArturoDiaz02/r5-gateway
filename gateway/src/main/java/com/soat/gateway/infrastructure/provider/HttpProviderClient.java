package com.soat.gateway.infrastructure.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.soat.gateway.application.port.EmissionProvider;
import com.soat.gateway.application.port.EmissionRequest;
import com.soat.gateway.application.port.ProviderFailure;
import com.soat.gateway.domain.emission.ProviderNotice;
import com.soat.gateway.domain.emission.ProviderStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public final class HttpProviderClient implements EmissionProvider {

    private final RestClient rest;

    public HttpProviderClient(String baseUrl, Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public void requestEmission(EmissionRequest request) {
        try {
            rest.post()
                    .uri("/emisiones")
                    .header("Idempotency-Key", request.reference())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "referencia", request.reference(),
                            "monto_centavos", request.quotedPremiumCents(),
                            "placa", request.plate()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict e) {
            return;
        } catch (RestClientException e) {
            throw new ProviderFailure("emission request failed for " + request.reference(), e);
        }
    }

    @Override
    public Optional<ProviderNotice> checkStatus(String reference) {
        try {
            StatusResponse response = rest.get()
                    .uri("/emisiones/{reference}", reference)
                    .retrieve()
                    .body(StatusResponse.class);
            return Optional.of(new ProviderNotice(
                    ProviderStatus.fromWire(response.status()), response.amountCents(), response.policyNumber()));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new ProviderFailure("status check failed for " + reference, e);
        }
    }

    record StatusResponse(
            @JsonProperty("estado") String status,
            @JsonProperty("monto_centavos") Long amountCents,
            @JsonProperty("numero_poliza") String policyNumber) {
    }
}
