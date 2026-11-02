-- ClassOS V4: digital classroom workspace and the assignment evaluation queue.

-- Metadata for every uploaded file. The file itself lives on disk under the storage
-- directory, named by this UUID, never by the uploader's file name (so a name like
-- "../../etc/passwd" can never become a path). Files are only served through the API,
-- after an access check.
CREATE TABLE stored_files (
    id             UUID         PRIMARY KEY,
    original_name  VARCHAR(255) NOT NULL,
    content_type   VARCHAR(100) NOT NULL,
    size_bytes     BIGINT       NOT NULL CONSTRAINT chk_file_size CHECK (size_bytes > 0),
    sha256         CHAR(64)     NOT NULL,
    uploaded_by    BIGINT       NOT NULL REFERENCES users (id),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Notes, slides and other material a teacher shares with a course offering.
CREATE TABLE materials (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    offering_id  BIGINT       NOT NULL REFERENCES course_offerings (id) ON DELETE CASCADE,
    title        VARCHAR(200) NOT NULL,
    description  TEXT,
    file_id      UUID         NOT NULL REFERENCES stored_files (id),
    created_by   BIGINT       NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_materials_offering ON materials (offering_id);

CREATE TABLE assignments (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    offering_id    BIGINT        NOT NULL REFERENCES course_offerings (id) ON DELETE CASCADE,
    title          VARCHAR(200)  NOT NULL,
    instructions   TEXT          NOT NULL DEFAULT '',
    attachment_id  UUID          REFERENCES stored_files (id),
    due_at         TIMESTAMPTZ   NOT NULL,
    grade_by       TIMESTAMPTZ   NOT NULL,   -- when marks should be returned: the scheduling deadline
    max_marks      NUMERIC(6, 2) NOT NULL CONSTRAINT chk_max_marks CHECK (max_marks > 0),
    accept_late    BOOLEAN       NOT NULL DEFAULT TRUE,
    -- Scheduling inputs for the evaluation queue (see core/include/classos/scheduler.hpp):
    priority       SMALLINT      NOT NULL DEFAULT 2 CONSTRAINT chk_assignment_priority CHECK (priority BETWEEN 1 AND 3),
    est_minutes    SMALLINT      NOT NULL DEFAULT 10 CONSTRAINT chk_est_minutes CHECK (est_minutes BETWEEN 1 AND 240),
    created_by     BIGINT        NOT NULL REFERENCES users (id),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_grade_after_due CHECK (grade_by >= due_at)
);
CREATE INDEX ix_assignments_offering ON assignments (offering_id);

-- One submission per student per assignment; resubmitting replaces the file and bumps
-- version. version also makes grading safe against a resubmission arriving mid-grading
-- (optimistic locking: the grade is saved only if the version the teacher saw is current).
CREATE TABLE submissions (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    assignment_id  BIGINT        NOT NULL REFERENCES assignments (id) ON DELETE CASCADE,
    student_id     BIGINT        NOT NULL REFERENCES users (id),
    file_id        UUID          NOT NULL REFERENCES stored_files (id),
    submitted_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    late           BOOLEAN       NOT NULL,
    version        INT           NOT NULL DEFAULT 1 CONSTRAINT chk_version CHECK (version >= 1),
    status         VARCHAR(10)   NOT NULL DEFAULT 'SUBMITTED'
        CONSTRAINT chk_submission_status CHECK (status IN ('SUBMITTED', 'GRADED')),
    marks          NUMERIC(6, 2) CONSTRAINT chk_marks_positive CHECK (marks IS NULL OR marks >= 0),
    feedback       TEXT,
    graded_by      BIGINT        REFERENCES users (id),
    graded_at      TIMESTAMPTZ,
    CONSTRAINT uq_one_submission_per_student UNIQUE (assignment_id, student_id),
    CONSTRAINT chk_graded_consistent CHECK (
        (status = 'GRADED') = (marks IS NOT NULL AND graded_at IS NOT NULL AND graded_by IS NOT NULL))
);
-- The evaluation queue reads exactly these rows; the partial index keeps it small.
CREATE INDEX ix_submissions_waiting ON submissions (assignment_id, submitted_at) WHERE status = 'SUBMITTED';
CREATE INDEX ix_submissions_student ON submissions (student_id);
CREATE TRIGGER trg_submission_student_role
    BEFORE INSERT ON submissions
    FOR EACH ROW EXECUTE FUNCTION enforce_user_role('student_id', 'STUDENT');

-- marks <= max_marks spans two tables, so a CHECK cannot express it.
CREATE FUNCTION enforce_max_marks() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    limit_marks NUMERIC;
BEGIN
    IF NEW.marks IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT max_marks INTO limit_marks FROM assignments WHERE id = NEW.assignment_id;
    IF NEW.marks > limit_marks THEN
        RAISE EXCEPTION 'marks % exceed the maximum of %', NEW.marks, limit_marks
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_submission_max_marks
    BEFORE INSERT OR UPDATE OF marks ON submissions
    FOR EACH ROW EXECUTE FUNCTION enforce_max_marks();
