package com.example.dqrp.messaging;

import com.example.dqrp.services.ChunkService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ChunkWorker {
    private final ChunkService chunkService;

    public ChunkWorker(ChunkService chunkService) { this.chunkService = chunkService; }

    @RabbitListener(queues = "chunk.process")
    public void onMessage(String chunkId) throws Exception {
        chunkService.process(UUID.fromString(chunkId));
    }
}