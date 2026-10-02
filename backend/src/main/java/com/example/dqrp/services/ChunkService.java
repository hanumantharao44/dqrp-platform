package com.example.dqrp.services;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@Service
public class ChunkService {
    private final JdbcTemplate jdbc;
    private final RowProcessor rowProcessor;
    private final Path uploadDir = Path.of("uploads");

    public ChunkService(JdbcTemplate jdbc, RowProcessor rowProcessor) {
        this.jdbc = jdbc;
        this.rowProcessor = rowProcessor;
    }

    @Transactional(rollbackFor = Exception.class)
    public void process(UUID chunkId) throws Exception {
        // 1. Redelivery guard
        Integer seen = jdbc.queryForObject(
                "SELECT count(*) FROM processed_message WHERE message_id = ?", Integer.class, chunkId);
        if (seen != null && seen > 0) return;

        // 2. Load chunk + batch, mark chunk as processing
        Map<String, Object> c = jdbc.queryForMap("""
            SELECT c.batch_id, c.row_start, c.row_end, b.source
            FROM batch_chunk c JOIN ingestion_batch b ON b.id = c.batch_id
            WHERE c.id = ?""", chunkId);
        UUID batchId = (UUID) c.get("batch_id");
        int rowStart = ((Number) c.get("row_start")).intValue();
        int rowEnd = ((Number) c.get("row_end")).intValue();
        String source = (String) c.get("source");

        jdbc.update("UPDATE batch_chunk SET status='PROCESSING', attempts = attempts + 1, updated_at = now() WHERE id=?", chunkId);

        // 3. Your Step 4 logic for this row range
        Path file = uploadDir.resolve(batchId + ".csv");
        RowProcessor.Counts counts = rowProcessor.processRows(batchId, source, file, rowStart, rowEnd);

        // 4. Mark chunk done, remember the message
        jdbc.update("UPDATE batch_chunk SET status='DONE', updated_at = now() WHERE id=?", chunkId);
        jdbc.update("INSERT INTO processed_message (message_id) VALUES (?)", chunkId);

        // 5. Add counts to the batch (this locks the batch row), then check if all chunks are done
        jdbc.update("""
            UPDATE ingestion_batch
            SET valid_rows = valid_rows + ?, invalid_rows = invalid_rows + ?,
                duplicate_rows = duplicate_rows + ?
            WHERE id = ?""", counts.valid(), counts.invalid(), counts.duplicate(), batchId);

        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM batch_chunk WHERE batch_id = ? AND status <> 'DONE'",
                Integer.class, batchId);

        if (remaining != null && remaining == 0) {
            jdbc.update("""
                UPDATE ingestion_batch
                SET status = CASE WHEN invalid_rows + duplicate_rows > 0
                                  THEN 'COMPLETED_WITH_ERRORS' ELSE 'COMPLETED' END,
                    completed_at = now()
                WHERE id = ?""", batchId);
        }
    }
}