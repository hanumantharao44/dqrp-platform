package com.example.dqrp.controllers;

import com.example.dqrp.services.BatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/batches")
public class BatchController {
        private final BatchService service;
        public BatchController(BatchService service) { this.service = service; }

        @PostMapping
        public ResponseEntity<Map<String, Object>> upload(
                @RequestParam("source") String source,
                @RequestParam("file") MultipartFile file) throws Exception {
            UUID id = service.create(source, file);
            return ResponseEntity.accepted().body(Map.of("batchId", id));
        }
    @GetMapping
    public List<BatchService.BatchDto> list() { return service.list(); }
}
