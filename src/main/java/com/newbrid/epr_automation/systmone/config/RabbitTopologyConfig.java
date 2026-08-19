package com.newbrid.epr_automation.systmone.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology shared by the control plane (publisher) and workers (consumers):
 * a durable work queue behind a direct exchange, plus a dead-letter exchange/queue for
 * jobs a worker cannot complete or deliver results for (FR-WRK-05, FR-CP-01).
 *
 * <p>Declared as {@code Declarable} beans; the {@code RabbitAdmin} materialises them once
 * a broker connection is available, so their declaration never blocks startup when the
 * broker is temporarily down.</p>
 */
@Configuration
public class RabbitTopologyConfig {

    public static final String EXCHANGE = "systmone.jobs.exchange";
    public static final String QUEUE = "systmone.jobs.q";
    public static final String ROUTING_KEY = "job";
    public static final String DLX = "systmone.jobs.dlx";
    public static final String DLQ = "systmone.jobs.dlq";

    @Bean
    public DirectExchange jobsExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public Queue jobsQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    public Binding jobsBinding() {
        return BindingBuilder.bind(jobsQueue()).to(jobsExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(DLQ);
    }
}
