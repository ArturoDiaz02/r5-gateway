package com.soat.gateway.infrastructure.config;

import com.soat.gateway.domain.emission.ReviewReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class LoggingFailedEmissionHandlerTest {

    private final LoggingFailedEmissionHandler handler = new LoggingFailedEmissionHandler();

    @Test
    void logsEveryCaseWithItsReference(CapturedOutput output) {
        handler.reviewRequired("SOAT-1");
        handler.expired("SOAT-2");
        handler.manualReview("SOAT-3", ReviewReason.AMOUNT_MISMATCH);
        handler.providerConflict("SOAT-4");

        assertThat(output.getOut()).contains(
                "REVIEW_REQUIRED reference=SOAT-1",
                "EXPIRED reference=SOAT-2",
                "MANUAL_REVIEW reference=SOAT-3 reason=AMOUNT_MISMATCH",
                "PROVIDER_CONFLICT reference=SOAT-4");
    }
}
