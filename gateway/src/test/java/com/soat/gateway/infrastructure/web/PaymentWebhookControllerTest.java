package com.soat.gateway.infrastructure.web;

import com.soat.gateway.application.port.ConcurrencyConflict;
import com.soat.gateway.application.service.RegisterNotice;
import com.soat.gateway.application.service.RegistrationOutcome;
import com.soat.gateway.domain.emission.ProviderNotice;
import com.soat.gateway.domain.emission.ProviderStatus;
import com.soat.gateway.domain.security.SignatureVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentWebhookControllerTest {

    private static final String SECRET = "secreto-prueba-v1";
    private static final String TIMESTAMP = "1794233002";
    private static final String BODY = "{\"id\":\"evt_01JC8Q2W7M\",\"tipo\":\"pago.actualizado\","
            + "\"creado_en\":\"2026-11-09T14:03:21.482Z\",\"data\":{\"referencia\":\"SOAT-883214\","
            + "\"estado\":\"APROBADO\",\"monto_centavos\":89130000}}";
    private static final String SIGNATURE = "6e4c5cd3315182229277b2c5fac58caeb142de384638706b51afd07ace65a6d3";

    private final RegisterNotice registerNotice = mock(RegisterNotice.class);
    private final SignatureVerifier verifier = new SignatureVerifier(
            Clock.fixed(Instant.ofEpochSecond(1794233003L), ZoneOffset.UTC), Duration.ofMinutes(5), SECRET, null);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new PaymentWebhookController(verifier, registerNotice))
            .build();

    private static MockHttpServletRequestBuilder webhook(String body, String timestamp, String signature) {
        MockHttpServletRequestBuilder request = post("/webhooks/pagos")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
        if (timestamp != null) {
            request.header("X-Timestamp", timestamp);
        }
        if (signature != null) {
            request.header("X-Firma", signature);
        }
        return request;
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((TIMESTAMP + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validSignatureRegistersTheNoticeAndAnswers200() throws Exception {
        when(registerNotice.register(anyString(), anyString(), any())).thenReturn(RegistrationOutcome.APPLIED);

        mvc.perform(webhook(BODY, TIMESTAMP, SIGNATURE)).andExpect(status().isOk());

        verify(registerNotice).register("evt_01JC8Q2W7M", "SOAT-883214",
                new ProviderNotice(ProviderStatus.APPROVED, 89_130_000L, null));
    }

    @ParameterizedTest
    @EnumSource(RegistrationOutcome.class)
    void everyAuthenticatedNoticeAnswers200(RegistrationOutcome outcome) throws Exception {
        when(registerNotice.register(anyString(), anyString(), any())).thenReturn(outcome);

        mvc.perform(webhook(BODY, TIMESTAMP, SIGNATURE)).andExpect(status().isOk());
    }

    @Test
    void invalidSignatureAnswers401AndRegistersNothing() throws Exception {
        mvc.perform(webhook(BODY, TIMESTAMP, SIGNATURE.replace('6', '7')))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));

        verifyNoInteractions(registerNotice);
    }

    @Test
    void missingSignatureHeadersAnswer401() throws Exception {
        mvc.perform(webhook(BODY, null, null)).andExpect(status().isUnauthorized());

        verifyNoInteractions(registerNotice);
    }

    @Test
    void timestampOutsideTheWindowAnswers401() throws Exception {
        String old = String.valueOf(1794233003L - 301);

        mvc.perform(webhook(BODY, old, SIGNATURE)).andExpect(status().isUnauthorized());

        verifyNoInteractions(registerNotice);
    }

    @Test
    void signatureIsVerifiedOverTheRawBytes() throws Exception {
        String withSpaces = "{ \"id\" : \"evt_2\", \"tipo\":\"pago.actualizado\", \"creado_en\":\"2026-11-09T14:03:21.482Z\","
                + " \"data\": { \"referencia\":\"SOAT-1\", \"estado\":\"PENDIENTE\", \"monto_centavos\": 100 } }";
        when(registerNotice.register(anyString(), anyString(), any())).thenReturn(RegistrationOutcome.IGNORED);

        mvc.perform(webhook(withSpaces, TIMESTAMP, sign(withSpaces))).andExpect(status().isOk());

        verify(registerNotice).register("evt_2", "SOAT-1", new ProviderNotice(ProviderStatus.PENDING, 100L, null));
    }

    @Test
    void invalidJsonWithValidSignatureAnswers400() throws Exception {
        String invalid = "{not json";

        mvc.perform(webhook(invalid, TIMESTAMP, sign(invalid))).andExpect(status().isBadRequest());

        verifyNoInteractions(registerNotice);
    }

    @Test
    void concurrencyConflictAnswers503SoTheProviderRetries() throws Exception {
        when(registerNotice.register(anyString(), anyString(), any()))
                .thenThrow(new ConcurrencyConflict("race", null));

        mvc.perform(webhook(BODY, TIMESTAMP, SIGNATURE)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void onlyAcceptsJson() throws Exception {
        mvc.perform(post("/webhooks/pagos").contentType(MediaType.TEXT_PLAIN).content(BODY)
                        .header("X-Timestamp", TIMESTAMP).header("X-Firma", SIGNATURE))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void onlyAcceptsPost() throws Exception {
        mvc.perform(get("/webhooks/pagos")).andExpect(status().isMethodNotAllowed());
    }
}
