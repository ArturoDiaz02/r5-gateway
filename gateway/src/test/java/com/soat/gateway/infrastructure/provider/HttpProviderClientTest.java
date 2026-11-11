package com.soat.gateway.infrastructure.provider;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.soat.gateway.application.port.EmissionRequest;
import com.soat.gateway.application.port.ProviderFailure;
import com.soat.gateway.domain.emission.ProviderNotice;
import com.soat.gateway.domain.emission.ProviderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpProviderClientTest {

    @RegisterExtension
    static final WireMockExtension PROVIDER = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final EmissionRequest REQUEST = new EmissionRequest(
            "SOAT-883214", "corr-1", Instant.parse("2026-11-09T14:00:00Z"), 89_130_000L, "ABC123");

    private final HttpProviderClient client = new HttpProviderClient(PROVIDER.baseUrl(), Duration.ofSeconds(10));

    @Test
    void sendsIdempotencyKeyAndBody() {
        PROVIDER.stubFor(post("/emisiones").willReturn(aResponse().withStatus(202)));

        client.requestEmission(REQUEST);

        PROVIDER.verify(exactly(1), postRequestedFor(urlEqualTo("/emisiones"))
                .withHeader("Idempotency-Key", equalTo("SOAT-883214"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"referencia":"SOAT-883214","monto_centavos":89130000,"placa":"ABC123"}""")));
    }

    @Test
    void answerInEightSecondsSucceeds() {
        PROVIDER.stubFor(post("/emisiones").willReturn(aResponse().withStatus(202).withFixedDelay(8_000)));

        assertThatCode(() -> client.requestEmission(REQUEST)).doesNotThrowAnyException();
    }

    @Test
    void answerInElevenSecondsTimesOutAtTen() {
        PROVIDER.stubFor(post("/emisiones").willReturn(aResponse().withStatus(202).withFixedDelay(11_000)));
        long start = System.nanoTime();

        assertThatThrownBy(() -> client.requestEmission(REQUEST)).isInstanceOf(ProviderFailure.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(10_900));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 429})
    void transientErrorsFailWithASingleCall(int status) {
        PROVIDER.stubFor(post("/emisiones").willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> client.requestEmission(REQUEST)).isInstanceOf(ProviderFailure.class);
        PROVIDER.verify(exactly(1), postRequestedFor(urlEqualTo("/emisiones")));
    }

    @Test
    void idempotencyConflictIsSuccess() {
        PROVIDER.stubFor(post("/emisiones").willReturn(aResponse().withStatus(409)));

        assertThatCode(() -> client.requestEmission(REQUEST)).doesNotThrowAnyException();
    }

    @Test
    void connectionResetIsAFailure() {
        PROVIDER.stubFor(post("/emisiones").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> client.requestEmission(REQUEST)).isInstanceOf(ProviderFailure.class);
    }

    @ParameterizedTest
    @CsvSource({"APROBADO, APPROVED", "PENDIENTE, PENDING", "RECHAZADO, REJECTED", "ANULADO, UNKNOWN"})
    void statusCheckMapsProviderStatuses(String fromProvider, ProviderStatus expected) {
        PROVIDER.stubFor(get("/emisiones/SOAT-883214").willReturn(okJson("""
                {"referencia":"SOAT-883214","estado":"%s"}""".formatted(fromProvider))));

        assertThat(client.checkStatus("SOAT-883214")).map(ProviderNotice::status).contains(expected);
    }

    @Test
    void approvedStatusCheckCarriesAmountAndPolicy() {
        PROVIDER.stubFor(get("/emisiones/SOAT-883214").willReturn(okJson("""
                {"referencia":"SOAT-883214","estado":"APROBADO","monto_centavos":89130000,
                 "numero_poliza":"POL-1","otro_campo":true}""")));

        assertThat(client.checkStatus("SOAT-883214"))
                .contains(new ProviderNotice(ProviderStatus.APPROVED, 89_130_000L, "POL-1"));
    }

    @Test
    void statusCheckOfUnknownEmissionIsEmpty() {
        PROVIDER.stubFor(get("/emisiones/SOAT-883214").willReturn(aResponse().withStatus(404)));

        assertThat(client.checkStatus("SOAT-883214")).isEmpty();
    }

    @Test
    void statusCheckErrorIsAFailure() {
        PROVIDER.stubFor(get("/emisiones/SOAT-883214").willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.checkStatus("SOAT-883214")).isInstanceOf(ProviderFailure.class);
    }

    @Test
    void malformedStatusCheckIsAFailure() {
        PROVIDER.stubFor(get("/emisiones/SOAT-883214").willReturn(okJson("{not json")));

        assertThatThrownBy(() -> client.checkStatus("SOAT-883214")).isInstanceOf(ProviderFailure.class);
    }
}
