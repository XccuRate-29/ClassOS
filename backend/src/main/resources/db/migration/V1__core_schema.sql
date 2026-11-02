-- ClassOS V1: identity/RBAC, academic structure, rooms & equipment, weekly timetable.
-- Attendance (Milestone 3) and assignments (Milestone 4) arrive as V2/V3 migrations.
--
-- Design rule: anything that must ALWAYS be true is enforced here, in the database,
-- not only in Java. Application checks give nice error messages; DB constraints
-- guarantee correctness under concurrent requests and multiple backend instances.

-- Needed so exclusion constraints can mix "=" on scalar columns with "&&" on ranges.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- A range type over time-of-day, for weekly slots such as Mon 09:00-10:00.
CREATE FUNCTION time_subdiff(x time, y time) RETURNS float8
    LANGUAGE sql IMMUTABLE STRICT
AS $$ SELECT EXTRACT(EPOCH FROM (x - y))::float8 $$;

CREATE TYPE timerange AS RANGE (subtype = time, subtype_diff = time_subdiff);

-- ---------------------------------------------------------------------------
-- Identity and roles
-- ---------------------------------------------------------------------------
CREATE TABLE departments (
    id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code  VARCHAR(16)  NOT NULL UNIQUE,
    name  VARCHAR(120) NOT NULL
);

-- One role per user keeps authorization simple. If a lab assistant also teaches,
-- they get two accounts, or this becomes a user_roles join table later.
CREATE TABLE users (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(100) NOT NULL,
    full_name      VARCHAR(120) NOT NULL,
    role           VARCHAR(20)  NOT NULL
        CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'TEACHER', 'STUDENT', 'LAB_ASSISTANT')),
    department_id  BIGINT REFERENCES departments (id),
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_users_email_lowercase CHECK (email = lower(email))
);
CREATE UNIQUE INDEX uq_users_email ON users (email);
CREATE INDEX ix_users_role ON users (role);

-- Reusable trigger: asserts that the user referenced by column TG_ARGV[0]
-- has role TG_ARGV[1]. A CHECK constraint cannot look at another table, so a
-- trigger is needed. Limitation: changing a user's role later is not re-checked.
CREATE FUNCTION enforce_user_role() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    uid      BIGINT;
    actual   TEXT;
BEGIN
    uid := (to_jsonb(NEW) ->> TG_ARGV[0])::BIGINT;
    IF uid IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT role INTO actual FROM users WHERE id = uid;
    IF actual IS DISTINCT FROM TG_ARGV[1] THEN
        RAISE EXCEPTION 'user % must have role %, found %', uid, TG_ARGV[1], COALESCE(actual, '<none>')
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------------
-- Academic structure
-- ---------------------------------------------------------------------------
CREATE TABLE academic_terms (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(40) NOT NULL UNIQUE,          -- e.g. 'Odd 2026'
    starts_on   DATE        NOT NULL,
    ends_on     DATE        NOT NULL,
    is_current  BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_term_dates CHECK (ends_on > starts_on)
);
-- At most one current term.
CREATE UNIQUE INDEX uq_one_current_term ON academic_terms (is_current) WHERE is_current;

CREATE TABLE sections (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    term_id        BIGINT      NOT NULL REFERENCES academic_terms (id),
    department_id  BIGINT      NOT NULL REFERENCES departments (id),
    name           VARCHAR(20) NOT NULL,              -- e.g. 'CSE-3A'
    semester       SMALLINT    NOT NULL CONSTRAINT chk_section_semester CHECK (semester BETWEEN 1 AND 10),
    strength       INT         NOT NULL CONSTRAINT chk_section_strength CHECK (strength > 0),
    CONSTRAINT uq_section_name UNIQUE (term_id, department_id, name),
    CONSTRAINT uq_section_term UNIQUE (id, term_id)   -- target of composite FKs below
);

-- term_id is copied here so that UNIQUE (term_id, student_id) can say
-- "one section per student per term". The composite FK guarantees the copy
-- always matches the section's real term.
CREATE TABLE section_enrollments (
    section_id  BIGINT NOT NULL,
    term_id     BIGINT NOT NULL,
    student_id  BIGINT NOT NULL REFERENCES users (id),
    PRIMARY KEY (section_id, student_id),
    CONSTRAINT uq_one_section_per_term UNIQUE (term_id, student_id),
    CONSTRAINT fk_enrollment_section FOREIGN KEY (section_id, term_id)
        REFERENCES sections (id, term_id) ON DELETE CASCADE
);
CREATE TRIGGER trg_enrollment_student_role
    BEFORE INSERT OR UPDATE ON section_enrollments
    FOR EACH ROW EXECUTE FUNCTION enforce_user_role('student_id', 'STUDENT');

CREATE TABLE courses (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    department_id            BIGINT       NOT NULL REFERENCES departments (id),
    code                     VARCHAR(20)  NOT NULL UNIQUE,   -- e.g. 'TCS-502'
    title                    VARCHAR(150) NOT NULL,
    credits                  SMALLINT     NOT NULL CONSTRAINT chk_course_credits CHECK (credits BETWEEN 1 AND 10),
    lecture_hours_per_week   SMALLINT     NOT NULL DEFAULT 0 CONSTRAINT chk_course_lecture CHECK (lecture_hours_per_week >= 0),
    lab_hours_per_week       SMALLINT     NOT NULL DEFAULT 0 CONSTRAINT chk_course_lab CHECK (lab_hours_per_week >= 0),
    CONSTRAINT chk_course_has_hours CHECK (lecture_hours_per_week + lab_hours_per_week > 0)
);

-- Who teaches which course to which section in which term.
CREATE TABLE course_offerings (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    term_id     BIGINT NOT NULL,
    course_id   BIGINT NOT NULL REFERENCES courses (id),
    section_id  BIGINT NOT NULL,
    teacher_id  BIGINT NOT NULL REFERENCES users (id),
    CONSTRAINT uq_offering UNIQUE (term_id, course_id, section_id),
    CONSTRAINT fk_offering_section FOREIGN KEY (section_id, term_id)
        REFERENCES sections (id, term_id),
    CONSTRAINT uq_offering_keys UNIQUE (id, term_id, teacher_id, section_id)  -- target for timetable FK
);
CREATE INDEX ix_offering_teacher ON course_offerings (teacher_id);
CREATE TRIGGER trg_offering_teacher_role
    BEFORE INSERT OR UPDATE ON course_offerings
    FOR EACH ROW EXECUTE FUNCTION enforce_user_role('teacher_id', 'TEACHER');

-- ---------------------------------------------------------------------------
-- Rooms and lab equipment (equipment totals feed the Banker's allocator)
-- ---------------------------------------------------------------------------
CREATE TABLE rooms (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(20) NOT NULL UNIQUE,           -- e.g. 'CSIT-LAB-2'
    building   VARCHAR(60) NOT NULL,
    capacity   INT         NOT NULL CONSTRAINT chk_room_capacity CHECK (capacity > 0),
    room_type  VARCHAR(20) NOT NULL CONSTRAINT chk_room_type CHECK (room_type IN ('CLASSROOM', 'LAB', 'SEMINAR_HALL')),
    active     BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE TABLE equipment_types (
    id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name  VARCHAR(80) NOT NULL UNIQUE
);

CREATE TABLE room_equipment (
    room_id            BIGINT NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    equipment_type_id  BIGINT NOT NULL REFERENCES equipment_types (id),
    total_units        INT    NOT NULL CONSTRAINT chk_equipment_units CHECK (total_units >= 0),
    PRIMARY KEY (room_id, equipment_type_id)
);

-- ---------------------------------------------------------------------------
-- Weekly timetable with database-level conflict detection
-- ---------------------------------------------------------------------------
-- teacher_id, section_id and term_id are copied from the offering so that the
-- exclusion constraints below can use them. The composite FK keeps the copies
-- correct; ON UPDATE CASCADE means reassigning an offering's teacher updates
-- every slot, and the teacher-overlap constraint is re-checked automatically.
CREATE TABLE timetable_slots (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    offering_id  BIGINT      NOT NULL,
    term_id      BIGINT      NOT NULL,
    teacher_id   BIGINT      NOT NULL,
    section_id   BIGINT      NOT NULL,
    room_id      BIGINT      NOT NULL REFERENCES rooms (id),
    day_of_week  SMALLINT    NOT NULL CONSTRAINT chk_slot_day CHECK (day_of_week BETWEEN 1 AND 7),  -- ISO: 1 = Monday
    start_time   TIME        NOT NULL,
    end_time     TIME        NOT NULL,
    slot_kind    VARCHAR(10) NOT NULL CONSTRAINT chk_slot_kind CHECK (slot_kind IN ('LECTURE', 'LAB')),
    -- Half-open [start, end): a 09:00-10:00 slot and a 10:00-11:00 slot do NOT overlap.
    period       timerange   GENERATED ALWAYS AS (timerange(start_time, end_time, '[)')) STORED,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_slot_times CHECK (end_time > start_time),
    CONSTRAINT fk_slot_offering FOREIGN KEY (offering_id, term_id, teacher_id, section_id)
        REFERENCES course_offerings (id, term_id, teacher_id, section_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT no_room_overlap EXCLUDE USING gist
        (term_id WITH =, room_id WITH =, day_of_week WITH =, period WITH &&),
    CONSTRAINT no_teacher_overlap EXCLUDE USING gist
        (term_id WITH =, teacher_id WITH =, day_of_week WITH =, period WITH &&),
    CONSTRAINT no_section_overlap EXCLUDE USING gist
        (term_id WITH =, section_id WITH =, day_of_week WITH =, period WITH &&)
);
-- Not enforced here (checked by the timetable engine in Milestone 2, because
-- they are rules with exceptions rather than absolute invariants):
--   room capacity >= section strength, LAB slots only in LAB rooms,
--   max teaching hours per day, lunch break.
