package com.soat.simulator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignerTest {

    @Test
    void signsLikeTheProviderWithTheGoldenVector() {
        String body = "{\"id\":\"evt_01JC8Q2W7M\",\"tipo\":\"pago.actualizado\","
                + "\"creado_en\":\"2026-11-09T14:03:21.482Z\",\"data\":{\"referencia\":\"SOAT-883214\","
                + "\"estado\":\"APROBADO\",\"monto_centavos\":89130000}}";

        assertThat(new Signer("secreto-prueba-v1").sign(1794233002L, body))
                .isEqualTo("6e4c5cd3315182229277b2c5fac58caeb142de384638706b51afd07ace65a6d3");
    }
}
