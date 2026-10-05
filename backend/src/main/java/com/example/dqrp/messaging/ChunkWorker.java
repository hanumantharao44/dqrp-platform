package com.example.dqrp.messaging;

import com.example.dqrp.services.ChunkFailureService;
import com.example.dqrp.services.ChunkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ChunkWorker {
    private static final Logger log = LoggerFactory.getLogger(ChunkWorker.class);
    private final ChunkService chunkService;
    private final ChunkFailureService failures;
    private final RabbitTemplate rabbit;

    public ChunkWorker(ChunkService chunkService, ChunkFailureService failures, RabbitTemplate rabbit) {
        this.chunkService = chunkService;
        this.failures = failures;
        this.rabbit = rabbit;
    }
    // constructor injection for all three

    @RabbitListener(queues = "chunk.process")
    public void onMessage(String chunkId) {
        UUID id = UUID.fromString(chunkId);
        try {
            chunkService.process(id);
        } catch (Exception e) {
            log.warn("Chunk {} failed", id, e);
            var out = failures.recordFailure(id, e);      // commits in its own transaction
            String queue = out.deadLettered()
                    ? "chunk.dlq"
                    : ChunkFailureService.RETRY_QUEUES[out.attempts() - 1];
            rabbit.convertAndSend("", queue, chunkId);    // default exchange, queue name as key
        }
        // returning normally acks the original message
    }
}