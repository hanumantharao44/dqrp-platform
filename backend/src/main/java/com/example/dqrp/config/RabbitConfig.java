package com.example.dqrp.config;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    @Bean
    Queue chunkQueue() { return QueueBuilder.durable("chunk.process").build(); }
    @Bean Queue retry10()  { return QueueBuilder.durable("chunk.retry.10s").ttl(10_000)
            .deadLetterExchange("").deadLetterRoutingKey("chunk.process").build(); }
    @Bean Queue retry60()  { return QueueBuilder.durable("chunk.retry.60s").ttl(60_000)
            .deadLetterExchange("").deadLetterRoutingKey("chunk.process").build(); }
    @Bean Queue retry300() { return QueueBuilder.durable("chunk.retry.300s").ttl(300_000)
            .deadLetterExchange("").deadLetterRoutingKey("chunk.process").build(); }
    @Bean Queue dlq()      { return QueueBuilder.durable("chunk.dlq").build(); }
}