package com.soat.gateway.infrastructure.config;

import com.soat.gateway.application.port.DelayedTaskScheduler;
import com.soat.gateway.application.port.EmissionProvider;
import com.soat.gateway.application.port.EmissionRepository;
import com.soat.gateway.application.port.FailedEmissionHandler;
import com.soat.gateway.application.port.RequestQueue;
import com.soat.gateway.application.port.UnitOfWork;
import com.soat.gateway.application.service.DecisionApplier;
import com.soat.gateway.application.service.MarkSendFailed;
import com.soat.gateway.application.service.ProcessRequest;
import com.soat.gateway.application.service.RegisterNotice;
import com.soat.gateway.application.service.RetryPolicy;
import com.soat.gateway.application.service.RunExpiration;
import com.soat.gateway.application.service.RunStatusCheck;
import com.soat.gateway.domain.security.SignatureVerifier;
import com.soat.gateway.domain.sla.StatusCheckSchedule;
import com.soat.gateway.infrastructure.messaging.Consumers;
import com.soat.gateway.infrastructure.messaging.MessageCodec;
import com.soat.gateway.infrastructure.messaging.RabbitDelayedTaskScheduler;
import com.soat.gateway.infrastructure.messaging.RabbitRequestQueue;
import com.soat.gateway.infrastructure.messaging.RabbitResultPublisher;
import com.soat.gateway.infrastructure.messaging.RabbitSender;
import com.soat.gateway.infrastructure.persistence.JdbcEmissionRepository;
import com.soat.gateway.infrastructure.persistence.JdbcOutboxRepository;
import com.soat.gateway.infrastructure.persistence.JdbcProviderEventRepository;
import com.soat.gateway.infrastructure.persistence.JdbcUnitOfWork;
import com.soat.gateway.infrastructure.provider.HttpProviderClient;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class GatewayConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MessageCodec messageCodec() {
        return new MessageCodec();
    }

    @Bean
    RabbitSender rabbitSender(RabbitTemplate rabbit) {
        return new RabbitSender(rabbit);
    }

    @Bean
    UnitOfWork unitOfWork(TransactionTemplate transactions) {
        return new JdbcUnitOfWork(transactions);
    }

    @Bean
    EmissionRepository emissionRepository(JdbcClient jdbc) {
        return new JdbcEmissionRepository(jdbc);
    }

    @Bean
    DelayedTaskScheduler delayedTaskScheduler(JdbcClient jdbc, RabbitSender sender, MessageCodec codec, Clock clock) {
        return new RabbitDelayedTaskScheduler(jdbc, sender, codec, clock);
    }

    @Bean
    RequestQueue requestQueue(RabbitSender sender, MessageCodec codec) {
        return new RabbitRequestQueue(sender, codec);
    }

    @Bean
    EmissionProvider emissionProvider(GatewayProperties properties) {
        return new HttpProviderClient(properties.provider().url(), properties.provider().timeout());
    }

    @Bean
    FailedEmissionHandler failedEmissionHandler() {
        return new LoggingFailedEmissionHandler();
    }

    @Bean
    SignatureVerifier signatureVerifier(GatewayProperties properties, Clock clock) {
        GatewayProperties.Hmac hmac = properties.hmac();
        String previous = hmac.previous() == null || hmac.previous().isBlank() ? null : hmac.previous();
        return new SignatureVerifier(clock, hmac.window(), hmac.current(), previous);
    }

    @Bean
    StatusCheckSchedule statusCheckSchedule(GatewayProperties properties) {
        return new StatusCheckSchedule(properties.statusChecks());
    }

    @Bean
    DecisionApplier decisionApplier(EmissionRepository repository, JdbcClient jdbc, RabbitSender sender,
                                    MessageCodec codec, FailedEmissionHandler handler, UnitOfWork unitOfWork,
                                    Clock clock) {
        return new DecisionApplier(repository, new JdbcOutboxRepository(jdbc),
                new RabbitResultPublisher(sender, codec), handler, unitOfWork, clock);
    }

    @Bean
    ProcessRequest processRequest(EmissionRepository repository, DelayedTaskScheduler scheduler,
                                  EmissionProvider provider, RequestQueue queue, DecisionApplier applier,
                                  UnitOfWork unitOfWork, StatusCheckSchedule schedule, GatewayProperties properties) {
        return new ProcessRequest(repository, scheduler, provider, queue, applier, unitOfWork, schedule,
                new RetryPolicy(properties.retry().maxAttempts(), properties.retry().delay()));
    }

    @Bean
    RegisterNotice registerNotice(JdbcClient jdbc, EmissionRepository repository, DecisionApplier applier,
                                  UnitOfWork unitOfWork) {
        return new RegisterNotice(new JdbcProviderEventRepository(jdbc), repository, applier, unitOfWork);
    }

    @Bean
    RunStatusCheck runStatusCheck(EmissionRepository repository, EmissionProvider provider,
                                  DelayedTaskScheduler scheduler, DecisionApplier applier,
                                  FailedEmissionHandler handler, StatusCheckSchedule schedule) {
        return new RunStatusCheck(repository, provider, scheduler, applier, handler, schedule);
    }

    @Bean
    RunExpiration runExpiration(EmissionRepository repository, EmissionProvider provider, DecisionApplier applier,
                                FailedEmissionHandler handler, Clock clock) {
        return new RunExpiration(repository, provider, applier, handler, clock);
    }

    @Bean
    MarkSendFailed markSendFailed(EmissionRepository repository, DecisionApplier applier) {
        return new MarkSendFailed(repository, applier);
    }

    @Bean
    SimpleMessageListenerContainer requestsConsumer(ConnectionFactory connections, ProcessRequest service,
                                                    MessageCodec codec, RabbitSender sender,
                                                    GatewayProperties properties) {
        return Consumers.container(connections, "emission.requests",
                Consumers.requests(service::process, codec, sender, properties.retry().internalErrorDelay()),
                properties.consumers().requests());
    }

    @Bean
    SimpleMessageListenerContainer statusChecksConsumer(ConnectionFactory connections, RunStatusCheck service,
                                                        MessageCodec codec, RabbitSender sender,
                                                        GatewayProperties properties) {
        return Consumers.container(connections, "emission.status-checks",
                Consumers.statusChecks(service::run, codec, sender, properties.retry().internalErrorDelay()),
                properties.consumers().statusChecks());
    }

    @Bean
    SimpleMessageListenerContainer expirationsConsumer(ConnectionFactory connections, RunExpiration service,
                                                       MessageCodec codec, RabbitSender sender,
                                                       GatewayProperties properties) {
        return Consumers.container(connections, "emission.expirations",
                Consumers.expirations(service::run, codec, sender, properties.retry().internalErrorDelay()),
                properties.consumers().expirations());
    }

    @Bean
    SimpleMessageListenerContainer deadLetterConsumer(ConnectionFactory connections, MarkSendFailed service,
                                                      MessageCodec codec, RabbitSender sender,
                                                      GatewayProperties properties) {
        return Consumers.container(connections, "emission.requests.dlq",
                Consumers.deadLetter(service::mark, codec, sender, properties.retry().internalErrorDelay()),
                properties.consumers().deadLetter());
    }
}
