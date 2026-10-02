package com.example.dqrp.services;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class BatchService {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbitTemplate;
    private final Path uploadDir = Path.of("uploads");
    private static final int CHUNK_SIZE = 1000;

    public BatchService(JdbcTemplate jdbc, RabbitTemplate rabbitTemplate) {
        this.jdbc = jdbc;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    public UUID create(String source, MultipartFile file) throws Exception {
        // 1. Validate the source
        if (!source.equals("SYSTEM_A") && !source.equals("SYSTEM_B"))
            throw new IllegalArgumentException("source must be SYSTEM_A or SYSTEM_B");

        // 2. Register the batch (throws DuplicateKeyException if same file again)
        byte[] bytes = file.getBytes();
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ingestion_batch (id, source, file_name, file_checksum, status)
            VALUES (?, ?, ?, ?, 'RECEIVED')""",
                id, source, file.getOriginalFilename(), sha256(bytes));

        // 3. Save the file to disk
        Files.createDirectories(uploadDir);
        Path path = uploadDir.resolve(id + ".csv");
        Files.write(path, bytes);

        // 4. Reject files with missing columns
        if (!hasRequiredHeaders(path)) {
            jdbc.update("UPDATE ingestion_batch SET status='FAILED', completed_at=now() WHERE id=?", id);
            return id;
        }

        // 5. Count the data rows
        int totalRows = countDataRows(path);
        if (totalRows == 0) {
            jdbc.update("UPDATE ingestion_batch SET status='COMPLETED', completed_at=now() WHERE id=?", id);
            return id;
        }

        // 6. Mark the batch as processing and store the total
        jdbc.update("UPDATE ingestion_batch SET status='PROCESSING', total_rows=? WHERE id=?",
                totalRows, id);


        // 7. Create one batch_chunk row per 1000 rows
        List<UUID> chunkIds = new ArrayList<>();
        int chunkIndex = 0;
        for (int start = 1; start <= totalRows; start += CHUNK_SIZE) {
            int end = Math.min(start + CHUNK_SIZE - 1, totalRows);
            UUID chunkId = UUID.randomUUID();
            jdbc.update("""
        INSERT INTO batch_chunk (id, batch_id, chunk_index, row_start, row_end, status, attempts)
        VALUES (?,?,?,?,?, 'PENDING', 0)""",
                    chunkId, id, chunkIndex, start, end);
            chunkIds.add(chunkId);
            chunkIndex++;
        }

        // 8. Send the messages only after the transaction has committed
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                chunkIds.forEach(cid -> rabbitTemplate.convertAndSend("chunk.process", cid.toString()));
            }
        });
        return id;
    }

    private boolean hasRequiredHeaders(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file);
             CSVParser p = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true).build().parse(r)) {
            return p.getHeaderNames().containsAll(
                    List.of("txn_ref", "account_id", "amount", "currency", "txn_date"));
        }
    }

    private int countDataRows(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file);
             CSVParser p = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true).build().parse(r)) {
            int n = 0;
            for (CSVRecord ignored : p) n++;
            return n;
        }
    }

    public record BatchDto(UUID id, String source, String fileName, String status,
                           int totalRows, int validRows, int invalidRows,
                           int duplicateRows, OffsetDateTime createdAt) {}

    public List<BatchDto> list() {
        return jdbc.query("SELECT * FROM ingestion_batch ORDER BY created_at DESC LIMIT 50",
                (rs, i) -> new BatchDto(
                        rs.getObject("id", UUID.class), rs.getString("source"),
                        rs.getString("file_name"), rs.getString("status"),
                        rs.getInt("total_rows"), rs.getInt("valid_rows"),
                        rs.getInt("invalid_rows"), rs.getInt("duplicate_rows"),
                        rs.getObject("created_at", OffsetDateTime.class)));
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}