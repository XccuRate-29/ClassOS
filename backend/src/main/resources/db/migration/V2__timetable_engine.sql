-- ClassOS V2: timetable engine support and ad-hoc room bookings.

-- How many days a week the term runs (5 = Mon-Fri, 6 = Mon-Sat).
ALTER TABLE academic_terms
    ADD COLUMN working_days SMALLINT NOT NULL DEFAULT 5
        CONSTRAINT chk_term_working_days CHECK (working_days BETWEEN 1 AND 7);

-- The period grid for a term, e.g. P1 09:00-09:55 ... P8 16:15-17:10.
-- break_after = TRUE means a lab block may not continue past this period (lunch).
CREATE TABLE period_slots (
    term_id      BIGINT   NOT NULL REFERENCES academic_terms (id) ON DELETE CASCADE,
    period_no    SMALLINT NOT NULL CONSTRAINT chk_period_no CHECK (period_no BETWEEN 1 AND 24),
    start_time   TIME     NOT NULL,
    end_time     TIME     NOT NULL,
    break_after  BOOLEAN  NOT NULL DEFAULT FALSE,
    period       timerange GENERATED ALWAYS AS (timerange(start_time, end_time, '[)')) STORED,
    PRIMARY KEY (term_id, period_no),
    CONSTRAINT chk_period_times CHECK (end_time > start_time),
    CONSTRAINT no_period_overlap EXCLUDE USING gist (term_id WITH =, period WITH &&)
);

-- Where a slot came from. Regenerating replaces only GENERATED slots that are not
-- locked; MANUAL slots and locked (pinned) slots are kept and treated as fixed.
ALTER TABLE timetable_slots
    ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'MANUAL'
        CONSTRAINT chk_slot_source CHECK (source IN ('MANUAL', 'GENERATED')),
    ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;

-- Lookups for the section / teacher / room timetable views.
CREATE INDEX ix_slots_term_section ON timetable_slots (term_id, section_id);
CREATE INDEX ix_slots_term_teacher ON timetable_slots (term_id, teacher_id);
CREATE INDEX ix_slots_term_room    ON timetable_slots (term_id, room_id);

CREATE TABLE teacher_unavailability (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    term_id      BIGINT       NOT NULL REFERENCES academic_terms (id) ON DELETE CASCADE,
    teacher_id   BIGINT       NOT NULL REFERENCES users (id),
    day_of_week  SMALLINT     NOT NULL CONSTRAINT chk_unavail_day CHECK (day_of_week BETWEEN 1 AND 7),
    start_time   TIME         NOT NULL,
    end_time     TIME         NOT NULL,
    reason       VARCHAR(200),
    period       timerange GENERATED ALWAYS AS (timerange(start_time, end_time, '[)')) STORED,
    CONSTRAINT chk_unavail_times CHECK (end_time > start_time),
    CONSTRAINT no_unavail_overlap EXCLUDE USING gist
        (term_id WITH =, teacher_id WITH =, day_of_week WITH =, period WITH &&)
);
CREATE TRIGGER trg_unavail_teacher_role
    BEFORE INSERT OR UPDATE ON teacher_unavailability
    FOR EACH ROW EXECUTE FUNCTION enforce_user_role('teacher_id', 'TEACHER');

-- One row per generation run: an audit trail and data for the evaluation report
-- (events placed, search effort, time taken).
CREATE TABLE timetable_runs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    term_id         BIGINT      NOT NULL REFERENCES academic_terms (id) ON DELETE CASCADE,
    requested_by    BIGINT      NOT NULL REFERENCES users (id),
    status          VARCHAR(10) NOT NULL CONSTRAINT chk_run_status CHECK (status IN ('COMPLETE', 'PARTIAL')),
    events_total    INT         NOT NULL,
    events_placed   INT         NOT NULL,
    nodes_explored  BIGINT      NOT NULL,
    backtracks      BIGINT      NOT NULL,
    solver_ms       BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_run_counts CHECK (events_placed BETWEEN 0 AND events_total)
);

-- Ad-hoc, dated bookings (extra lab session, make-up class, seminar).
-- The exclusion constraint stops two bookings overlapping in one room on one date.
-- Clashes with the WEEKLY timetable cannot be expressed as a single constraint
-- (different tables, weekday vs date), so BookingService checks them while holding
-- a row lock on the room. See docs/concurrency.md.
CREATE TABLE room_bookings (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_id       BIGINT       NOT NULL REFERENCES rooms (id),
    booked_by     BIGINT       NOT NULL REFERENCES users (id),
    booking_date  DATE         NOT NULL,
    start_time    TIME         NOT NULL,
    end_time      TIME         NOT NULL,
    purpose       VARCHAR(200) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    period        timerange GENERATED ALWAYS AS (timerange(start_time, end_time, '[)')) STORED,
    CONSTRAINT chk_booking_times CHECK (end_time > start_time),
    CONSTRAINT no_booking_overlap EXCLUDE USING gist
        (room_id WITH =, booking_date WITH =, period WITH &&)
);
CREATE INDEX ix_bookings_user ON room_bookings (booked_by, booking_date);
