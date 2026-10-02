package com.example.dqrp.services;

import com.example.dqrp.model.RuleError;
import com.example.dqrp.model.TransactionRow;
import com.example.dqrp.validation.ValidationRule;
import tools.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RowProcessor {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final List<ValidationRule> rules;

    public RowProcessor(JdbcTemplate jdbc, ObjectMapper mapper, List<ValidationRule> rules) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rules = rules;
    }

    // The result object that BatchService reads: c.valid(), c.invalid(), c.duplicate()
    public record Counts(int valid, int invalid, int duplicate) {}

    public Counts processRows(UUID batchId, String source, Path file,
                              int startRow, int endRow) throws Exception {
        int valid = 0, invalid = 0, dup = 0;
        try (Reader reader = Files.newBufferedReader(file);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true).build().parse(reader)) {

            int rowNo = 0;
            for (CSVRecord rec : parser) {
                rowNo++;
                if (rowNo < startRow) continue;
                if (rowNo > endRow) break;

                TransactionRow row = new TransactionRow(rowNo, rec.get("txn_ref"), rec.get("account_id"),
                        rec.get("amount"), rec.get("currency"), rec.get("txn_date"));
                List<RuleError> errors = rules.stream()
                        .flatMap(r -> r.check(row).stream()).toList();

                String status = errors.isEmpty() ? "VALID" : "INVALID";
                Long id = insert(batchId, source, row, rec.toMap(), status);

                if (id == null) {                       // unique index rejected it
                    insert(batchId, source, row, rec.toMap(), "DUPLICATE");
                    dup++;
                } else if (errors.isEmpty()) {
                    valid++;
                } else {
                    invalid++;
                    saveErrors(id, errors);
                }
            }
        }
        return new Counts(valid, invalid, dup);
    }

    private Long insert(UUID batchId, String source, TransactionRow row,
                        Map<String, String> raw, String status) throws Exception {
        return jdbc.query("""
            INSERT INTO transaction_record
              (batch_id, source, row_number, txn_ref, account_id, amount, currency,
               txn_date, record_hash, raw_payload, status)
            VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?)
            ON CONFLICT (source, txn_ref) WHERE status = 'VALID' DO NOTHING
            RETURNING id""",
                rs -> rs.next() ? rs.getLong(1) : null,
                batchId, source, row.rowNumber(), row.txnRef(), row.accountId(),
                parseDecimal(row.amount()), row.currency(), parseDate(row.txnDate()),
                sha256(row), mapper.writeValueAsString(raw), status);
    }

    private void saveErrors(long recordId, List<RuleError> errors) {
        for (RuleError e : errors)
            jdbc.update("""
                INSERT INTO validation_error (record_id, rule_code, field_name, message)
                VALUES (?,?,?,?)""", recordId, e.code(), e.field(), e.message());
    }

    private static BigDecimal parseDecimal(String s) {
        try { return new BigDecimal(s.trim()); } catch (Exception e) { return null; }
    }

    private static LocalDate parseDate(String s) {
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { return null; }
    }

    private static String sha256(TransactionRow r) {
        String text = String.join("|", r.txnRef(), r.accountId(), r.amount(), r.currency(), r.txnDate());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}