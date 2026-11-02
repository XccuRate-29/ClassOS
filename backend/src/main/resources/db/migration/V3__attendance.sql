-- ClassOS V3: secure attendance.
--
-- Anti-proxy rules that the DATABASE enforces (the application cannot get them wrong):
--   * one record per student per session          -> PRIMARY KEY (session_id, student_id)
--   * one phone marks at most one student a class  -> UNIQUE (session_id, device_hash)
--   * one registered phone per student, and a phone
--     belongs to only one student                  -> student_devices PK + UNIQUE (device_hash)
--   * at most one open session per course offering -> partial unique index

-- Lets class_sessions check that its section really is the offering's section.
ALTER TABLE course_offerings ADD CONSTRAINT uq_offering_section UNIQUE (id, section_id);

CREATE TABLE class_sessions (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    term_id        BIGINT      NOT NULL REFERENCES academic_terms (id),
    offering_id    BIGINT      NOT NULL,
    section_id     BIGINT      NOT NULL,
    -- Who actually took the class (could differ from the offering's teacher later, e.g. a substitute).
    teacher_id     BIGINT      NOT NULL REFERENCES users (id),
    slot_id        BIGINT      REFERENCES timetable_slots (id) ON DELETE SET NULL,
    room_id        BIGINT      REFERENCES rooms (id),
    session_date   DATE        NOT NULL,
    status         VARCHAR(10) NOT NULL DEFAULT 'OPEN' CONSTRAINT chk_session_status CHECK (status IN ('OPEN', 'CLOSED')),
    -- Random per session. Codes = HMAC(server key, session id, nonce, time window);
    -- the server key is never stored in the database.
    code_nonce     BYTEA       NOT NULL CONSTRAINT chk_nonce_length CHECK (octet_length(code_nonce) >= 16),
    opened_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    auto_close_at  TIMESTAMPTZ NOT NULL,
    closed_at      TIMESTAMPTZ,
    CONSTRAINT fk_session_offering FOREIGN KEY (offering_id, section_id)
        REFERENCES course_offerings (id, section_id),
    CONSTRAINT chk_closed_consistent CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT chk_auto_close_after_open CHECK (auto_close_at > opened_at)
);
CREATE UNIQUE INDEX uq_one_open_session_per_offering ON class_sessions (offering_id) WHERE status = 'OPEN';
CREATE UNIQUE INDEX uq_one_open_session_per_teacher ON class_sessions (teacher_id) WHERE status = 'OPEN';
CREATE INDEX ix_sessions_section_date ON class_sessions (section_id, session_date);
CREATE INDEX ix_sessions_open_expiry ON class_sessions (auto_close_at) WHERE status = 'OPEN';
CREATE TRIGGER trg_session_teacher_role
    BEFORE INSERT OR UPDATE ON class_sessions
    FOR EACH ROW EXECUTE FUNCTION enforce_user_role('teacher_id', 'TEACHER');

-- device_hash = SHA-256 (hex) of a random id the student's browser generates once.
-- The raw id is never stored, so the table cannot be used to impersonate a device.
CREATE TABLE student_devices (
    student_id     BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    device_hash    CHAR(64)    NOT NULL CONSTRAINT uq_device_one_student UNIQUE,
    registered_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_device_student_role
    BEFORE INSERT OR UPDATE ON student_devices
    FOR EACH ROW EXECUTE FUNCTION enforce_user_role('student_id', 'STUDENT');

CREATE TABLE attendance_records (
    session_id   BIGINT      NOT NULL REFERENCES class_sessions (id) ON DELETE CASCADE,
    student_id   BIGINT      NOT NULL REFERENCES users (id),
    status       VARCHAR(10) NOT NULL CONSTRAINT chk_record_status CHECK (status IN ('PRESENT', 'ABSENT')),
    -- QR = scanned link, OTP = typed code, MANUAL = set by the teacher, AUTO = absent when the session closed
    method       VARCHAR(10) NOT NULL CONSTRAINT chk_record_method CHECK (method IN ('QR', 'OTP', 'MANUAL', 'AUTO')),
    device_hash  CHAR(64),
    client_ip    INET,
    marked_by    BIGINT      REFERENCES users (id),
    marked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, student_id),
    -- NULLs are distinct in a UNIQUE constraint, so MANUAL/AUTO rows (no device) never collide.
    CONSTRAINT uq_one_device_per_session UNIQUE (session_id, device_hash),
    CONSTRAINT chk_self_marking_has_device CHECK (method IN ('MANUAL', 'AUTO') OR device_hash IS NOT NULL),
    CONSTRAINT chk_manual_has_marker CHECK (method <> 'MANUAL' OR marked_by IS NOT NULL)
);
CREATE INDEX ix_records_student ON attendance_records (student_id);
CREATE TRIGGER trg_record_student_role
    BEFORE INSERT ON attendance_records
    FOR EACH ROW EXECUTE FUNCTION enforce_user_role('student_id', 'STUDENT');

-- Every marking attempt, accepted or not: the rate limit reads it, and it is the
-- evidence for the report (how many expired codes were tried = forwarded screenshots).
CREATE TABLE attendance_attempts (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id    BIGINT      NOT NULL REFERENCES class_sessions (id) ON DELETE CASCADE,
    student_id    BIGINT      NOT NULL REFERENCES users (id),
    outcome       VARCHAR(20) NOT NULL,
    method        VARCHAR(10),
    device_hash   CHAR(64),
    client_ip     INET,
    attempted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_attempts_session_student ON attendance_attempts (session_id, student_id);
