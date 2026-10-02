-- =====================================================================
-- Enterprise Data Quality & Reconciliation Platform - initial schema
-- Statuses are stored as TEXT with CHECK constraints (easy to evolve
-- with Flyway, unlike native enums).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Ingestion batches: one row per uploaded file
-- ---------------------------------------------------------------------
CREATE TABLE ingestion_batch (
    id              UUID PRIMARY KEY,
    source          TEXT        NOT NULL CHECK (source IN ('SYSTEM_A', 'SYSTEM_B')),
    file_name       TEXT        NOT NULL,
    file_checksum   CHAR(64)    NOT NULL,           -- SHA-256 of file content
    status          TEXT        NOT NULL CHECK (status IN
                        ('RECEIVED', 'PROCESSING', 'COMPLETED',
                         'COMPLETED_WITH_ERRORS', 'FAILED')),
    total_rows      INTEGER     NOT NULL DEFAULT 0,
    valid_rows      INTEGER     NOT NULL DEFAULT 0,
    invalid_rows    INTEGER     NOT NULL DEFAULT 0,
    duplicate_rows  INTEGER     NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    -- File-level idempotency: uploading the same file twice is rejected
    CONSTRAINT uq_batch_source_checksum UNIQUE (source, file_checksum)
);

CREATE INDEX idx_batch_status ON ingestion_batch (status);
CREATE INDEX idx_batch_created ON ingestion_batch (created_at DESC);

-- ---------------------------------------------------------------------
-- 2. Chunks: a batch is split into fixed-size chunks (e.g. 1000 rows).
--    One RabbitMQ message == one chunk. This is the unit of retry and
--    the answer to "what if the worker crashes mid-batch?"
-- ---------------------------------------------------------------------
CREATE TABLE batch_chunk (
    id              UUID PRIMARY KEY,
    batch_id        UUID        NOT NULL REFERENCES ingestion_batch (id) ON DELETE CASCADE,
    chunk_index     INTEGER     NOT NULL,
    row_start       INTEGER     NOT NULL,           -- inclusive, 1-based data row
    row_end         INTEGER     NOT NULL,           -- inclusive
    status          TEXT        NOT NULL CHECK (status IN
                        ('PENDING', 'PROCESSING', 'DONE', 'FAILED', 'DEAD_LETTERED')),
    attempts        INTEGER     NOT NULL DEFAULT 0,
    last_error      TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_chunk_batch_index UNIQUE (batch_id, chunk_index)
);

CREATE INDEX idx_chunk_batch_status ON batch_chunk (batch_id, status);

-- ---------------------------------------------------------------------
-- 3. Transaction records: every row from every file, with its outcome
-- ---------------------------------------------------------------------
CREATE TABLE transaction_record (
    id              BIGSERIAL PRIMARY KEY,
    batch_id        UUID        NOT NULL REFERENCES ingestion_batch (id) ON DELETE CASCADE,
    chunk_id        UUID        REFERENCES batch_chunk (id) ON DELETE SET NULL,
    source          TEXT        NOT NULL CHECK (source IN ('SYSTEM_A', 'SYSTEM_B')),
    row_number      INTEGER     NOT NULL,
    txn_ref         TEXT,                            -- business key (nullable: may be invalid)
    account_id      TEXT,
    amount          NUMERIC(19, 4),
    currency        CHAR(3),
    txn_date        DATE,
    record_hash     CHAR(64),                        -- SHA-256 of normalized business fields
    raw_payload     JSONB       NOT NULL,            -- original row, untouched
    status          TEXT        NOT NULL CHECK (status IN ('VALID', 'INVALID', 'DUPLICATE')),
    duplicate_of    BIGINT      REFERENCES transaction_record (id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Reprocessing the same row of the same batch is a no-op (idempotency)
    CONSTRAINT uq_record_batch_row UNIQUE (batch_id, row_number)
);

-- Duplicate detection safety net: only ONE valid record per (source, txn_ref).
-- Even if two consumers race, the database guarantees correctness.
CREATE UNIQUE INDEX uq_valid_txn_per_source
    ON transaction_record (source, txn_ref)
    WHERE status = 'VALID';

CREATE INDEX idx_record_batch_status ON transaction_record (batch_id, status);
CREATE INDEX idx_record_source_ref   ON transaction_record (source, txn_ref);

-- ---------------------------------------------------------------------
-- 4. Validation errors: one row per broken rule per record
-- ---------------------------------------------------------------------
CREATE TABLE validation_error (
    id              BIGSERIAL PRIMARY KEY,
    record_id       BIGINT      NOT NULL REFERENCES transaction_record (id) ON DELETE CASCADE,
    rule_code       TEXT        NOT NULL,            -- e.g. AMOUNT_NEGATIVE, DATE_FUTURE
    field_name      TEXT,
    message         TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_validation_record ON validation_error (record_id);
CREATE INDEX idx_validation_rule   ON validation_error (rule_code);

-- ---------------------------------------------------------------------
-- 5. Idempotent consumer: remember which messages were fully handled
-- ---------------------------------------------------------------------
CREATE TABLE processed_message (
    message_id      UUID PRIMARY KEY,                -- = chunk id (or explicit messageId header)
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- 6. Dead letters: chunks that exhausted all retries (for the dashboard)
-- ---------------------------------------------------------------------
CREATE TABLE dead_letter (
    id              BIGSERIAL PRIMARY KEY,
    chunk_id        UUID        NOT NULL REFERENCES batch_chunk (id) ON DELETE CASCADE,
    batch_id        UUID        NOT NULL REFERENCES ingestion_batch (id) ON DELETE CASCADE,
    error_message   TEXT,
    payload         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at     TIMESTAMPTZ                      -- set when manually replayed
);

CREATE INDEX idx_dead_letter_open ON dead_letter (created_at) WHERE resolved_at IS NULL;

-- ---------------------------------------------------------------------
-- 7. Reconciliation
-- ---------------------------------------------------------------------
CREATE TABLE reconciliation_run (
    id                  UUID PRIMARY KEY,
    batch_a_id          UUID        NOT NULL REFERENCES ingestion_batch (id),
    batch_b_id          UUID        NOT NULL REFERENCES ingestion_batch (id),
    status              TEXT        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    amount_tolerance    NUMERIC(19, 4) NOT NULL DEFAULT 0.01,  -- rounding tolerance
    date_tolerance_days INTEGER     NOT NULL DEFAULT 1,        -- timing offset tolerance
    matched_count       INTEGER     NOT NULL DEFAULT 0,
    missing_in_a_count  INTEGER     NOT NULL DEFAULT 0,
    missing_in_b_count  INTEGER     NOT NULL DEFAULT 0,
    mismatch_count      INTEGER     NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);

CREATE TABLE reconciliation_result (
    id              BIGSERIAL PRIMARY KEY,
    run_id          UUID        NOT NULL REFERENCES reconciliation_run (id) ON DELETE CASCADE,
    txn_ref         TEXT        NOT NULL,
    record_a_id     BIGINT      REFERENCES transaction_record (id),
    record_b_id     BIGINT      REFERENCES transaction_record (id),
    result_type     TEXT        NOT NULL CHECK (result_type IN
                        ('MATCHED', 'MISSING_IN_A', 'MISSING_IN_B', 'AMOUNT_MISMATCH')),
    amount_diff     NUMERIC(19, 4),
    date_diff_days  INTEGER,
    CONSTRAINT uq_recon_run_ref UNIQUE (run_id, txn_ref)
);

CREATE INDEX idx_recon_result_type ON reconciliation_result (run_id, result_type);

-- ---------------------------------------------------------------------
-- 8. Audit log: append-only history of every state change
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
    id              BIGSERIAL PRIMARY KEY,
    entity_type     TEXT        NOT NULL,            -- BATCH, CHUNK, RECONCILIATION_RUN, ...
    entity_id       TEXT        NOT NULL,
    event           TEXT        NOT NULL,            -- e.g. STATUS_CHANGED, RETRY_SCHEDULED
    from_status     TEXT,
    to_status       TEXT,
    details         JSONB,
    actor           TEXT        NOT NULL DEFAULT 'system',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_entity  ON audit_log (entity_type, entity_id, created_at);
CREATE INDEX idx_audit_created ON audit_log (created_at DESC);

-- Make the audit log truly append-only at the database level
CREATE OR REPLACE FUNCTION audit_log_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_log is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_no_update
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
