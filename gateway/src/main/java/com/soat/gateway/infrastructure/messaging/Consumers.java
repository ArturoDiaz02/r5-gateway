package com.soat.gateway.infrastructure.messaging;

import com.soat.gateway.application.port.EmissionRequest;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class Consumers {

    private Consumers() {
    }

    public static MessageListener requests(BiConsumer<EmissionRequest, Integer> process, MessageCodec codec,
                                           RabbitSender sender, Duration retryDelay) {
        return new RetryingListener("requests",
                message -> process.accept(codec.readRequest(message.getBody()), attempt(message)),
                sender, retryDelay);
    }

    public static MessageListener statusChecks(BiConsumer<String, Integer> run, MessageCodec codec,
                                               RabbitSender sender, Duration retryDelay) {
        return new RetryingListener("status-checks", message -> {
            MessageCodec.StatusCheckMessage check = codec.readStatusCheck(message.getBody());
            run.accept(check.reference(), check.attempt());
        }, sender, retryDelay);
    }

    public static MessageListener expirations(Consumer<String> run, MessageCodec codec,
                                              RabbitSender sender, Duration retryDelay) {
        return new RetryingListener("expirations",
                message -> run.accept(codec.readExpiration(message.getBody())),
                sender, retryDelay);
    }

    public static MessageListener deadLetter(Consumer<String> mark, MessageCodec codec,
                                             RabbitSender sender, Duration retryDelay) {
        return new RetryingListener("requests.dlq",
                message -> mark.accept(codec.readRequest(message.getBody()).reference()),
                sender, retryDelay);
    }

    public static SimpleMessageListenerContainer container(ConnectionFactory connections, String queue,
                                                           MessageListener listener, int consumers) {
        SimpleAsyncTaskExecutor threads = new SimpleAsyncTaskExecutor("rabbit-" + queue + "-");
        threads.setVirtualThreads(true);
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connections);
        container.setQueueNames(queue);
        container.setMessageListener(listener);
        container.setConcurrentConsumers(consumers);
        container.setPrefetchCount(1);
        container.setAcknowledgeMode(AcknowledgeMode.AUTO);
        container.setDefaultRequeueRejected(false);
        container.setTaskExecutor(threads);
        container.setShutdownTimeout(20_000);
        return container;
    }

    private static int attempt(Message message) {
        Integer attempt = message.getMessageProperties().getHeader(MessageCodec.ATTEMPT_HEADER);
        return attempt != null ? attempt : 1;
    }
}
