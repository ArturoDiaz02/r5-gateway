package com.soat.gateway.application.service;

import com.soat.gateway.application.fakes.InMemoryDelayedTaskScheduler;
import com.soat.gateway.application.fakes.InMemoryRequestQueue;
import com.soat.gateway.domain.emission.EmissionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.soat.gateway.application.service.Scenario.EXPIRATION;
import static com.soat.gateway.application.service.Scenario.REFERENCE;
import static com.soat.gateway.application.service.Scenario.request;
import static org.assertj.core.api.Assertions.assertThat;

class ProcessRequestTest {

    private final Scenario scenario = new Scenario();

    @Test
    void persistsAndSchedulesTasksBeforeCallingTheProvider() {
        scenario.processRequest.process(request(), 1);

        assertThat(scenario.journal).containsExactly(
                "insert " + REFERENCE,
                "scheduleStatusCheck " + REFERENCE + " 1",
                "scheduleExpiration " + REFERENCE,
                "requestEmission " + REFERENCE);
        assertThat(scenario.status()).isEqualTo(EmissionStatus.SENT);
    }

    @Test
    void schedulesFirstCheckAtFiveSecondsAndExpirationOneHourBeforeTheDeadline() {
        scenario.processRequest.process(request(), 1);

        assertThat(scenario.scheduler.statusCheck(REFERENCE, 1))
                .contains(new InMemoryDelayedTaskScheduler.StatusCheck(REFERENCE, 1, Duration.ofSeconds(5)));
        assertThat(scenario.scheduler.expiration(REFERENCE)).contains(EXPIRATION);
    }

    @Test
    void noTransactionIsOpenWhileCallingTheProvider() {
        scenario.processRequest.process(request(), 1);

        assertThat(scenario.provider.requestsInsideTransaction()).containsExactly(false);
    }

    @Test
    void alreadySentRequestIsNotSentAgain() {
        scenario.processRequest.process(request(), 1);
        scenario.processRequest.process(request(), 1);

        assertThat(scenario.provider.requests()).isEqualTo(1);
    }

    @Test
    void redeliveryWhileRequestedDoesNotDuplicateTasks() {
        scenario.provider.failRequests(true);
        scenario.processRequest.process(request(), 1);
        scenario.provider.failRequests(false);

        scenario.processRequest.process(request(), 2);

        assertThat(scenario.scheduler.count()).isEqualTo(2);
        assertThat(scenario.provider.requests()).isEqualTo(2);
        assertThat(scenario.status()).isEqualTo(EmissionStatus.SENT);
    }

    @Test
    void providerFailureRetriesWithTheNextAttemptAfterTenSeconds() {
        scenario.provider.failRequests(true);

        scenario.processRequest.process(request(), 1);

        assertThat(scenario.queue.retries())
                .containsExactly(new InMemoryRequestQueue.Retry(request(), 2, Duration.ofSeconds(10)));
        assertThat(scenario.queue.deadLetter()).isEmpty();
        assertThat(scenario.status()).isEqualTo(EmissionStatus.REQUESTED);
    }

    @Test
    void fifthFailureGoesToTheDeadLetterQueue() {
        scenario.provider.failRequests(true);

        scenario.processRequest.process(request(), 5);

        assertThat(scenario.queue.retries()).isEmpty();
        assertThat(scenario.queue.deadLetter()).containsExactly(request());
    }

    @Test
    void webhookThatWinsTheRaceIsNotOverwritten() {
        scenario.provider.onRequest(() -> scenario.repository.forceStatus(REFERENCE, EmissionStatus.ISSUED));

        scenario.processRequest.process(request(), 1);

        assertThat(scenario.status()).isEqualTo(EmissionStatus.ISSUED);
    }

    @Test
    void manualReprocessingFromSendFailed() {
        scenario.in(EmissionStatus.SEND_FAILED);

        scenario.processRequest.process(request(), 1);

        assertThat(scenario.status()).isEqualTo(EmissionStatus.SENT);
    }
}
