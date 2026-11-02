# ClassOS database schema

Source of truth: the Flyway migrations in `backend/src/main/resources/db/migration/`.
This document explains the design; if the two disagree, the SQL wins.

## ER diagram

```mermaid
erDiagram
    departments ||--o{ users : "belongs to (optional)"
    departments ||--o{ sections : has
    departments ||--o{ courses : offers
    academic_terms ||--o{ sections : "runs in"
    sections ||--o{ section_enrollments : contains
    users ||--o{ section_enrollments : "student enrols"
    courses ||--o{ course_offerings : "offered as"
    sections ||--o{ course_offerings : receives
    users ||--o{ course_offerings : "teacher teaches"
    course_offerings ||--o{ timetable_slots : "scheduled in"
    rooms ||--o{ timetable_slots : hosts
    rooms ||--o{ room_equipment : stocks
    equipment_types ||--o{ room_equipment : "counted in"

    users {
        bigint id PK
        varchar email UK "lower-case enforced"
        varchar password_hash "bcrypt"
        varchar role "ADMIN | TEACHER | STUDENT | LAB_ASSISTANT"
        bigint department_id FK
        boolean active
    }
    academic_terms {
        bigint id PK
        varchar name UK
        date starts_on
        date ends_on
        boolean is_current "at most one true"
    }
    sections {
        bigint id PK
        bigint term_id FK
        bigint department_id FK
        varchar name
        smallint semester
        int strength
    }
    section_enrollments {
        bigint section_id PK,FK
        bigint student_id PK,FK
        bigint term_id FK "one section per student per term"
    }
    courses {
        bigint id PK
        varchar code UK
        smallint credits
        smallint lecture_hours_per_week
        smallint lab_hours_per_week
    }
    course_offerings {
        bigint id PK
        bigint term_id FK
        bigint course_id FK
        bigint section_id FK
        bigint teacher_id FK
    }
    rooms {
        bigint id PK
        varchar code UK
        int capacity
        varchar room_type "CLASSROOM | LAB | SEMINAR_HALL"
    }
    equipment_types {
        bigint id PK
        varchar name UK
    }
    room_equipment {
        bigint room_id PK,FK
        bigint equipment_type_id PK,FK
        int total_units "Banker's total vector"
    }
    timetable_slots {
        bigint id PK
        bigint offering_id FK
        bigint term_id FK
        bigint teacher_id FK
        bigint section_id FK
        bigint room_id FK
        smallint day_of_week "ISO 1-7"
        time start_time
        time end_time
        timerange period "generated, half-open"
    }
```

## Key design decisions

**Conflicts are prevented by the database, not only by Java.** `timetable_slots` has three
exclusion constraints: no two slots in the same term may overlap for the same room, the
same teacher, or the same section. A check-then-insert in Java has a race window: two
concurrent requests can both see "free" and both insert. An exclusion constraint closes
that window regardless of how many requests or backend instances run in parallel. This
needs the `btree_gist` extension, which is why it is the first line of the migration.

**Half-open time ranges `[start, end)`.** A 09:00–10:00 class and a 10:00–11:00 class
do not conflict. With closed ranges they would, and back-to-back scheduling would fail.

**Copied columns protected by composite foreign keys.** `timetable_slots` stores
`teacher_id`, `section_id` and `term_id` even though they are derivable from the offering,
because exclusion constraints can only use columns of their own table. Normally that
copying risks inconsistency; here the composite FK
`(offering_id, term_id, teacher_id, section_id) → course_offerings` makes a mismatched copy
impossible, and `ON UPDATE CASCADE` propagates a teacher reassignment to every slot. If the
new teacher is busy at any of those times, the whole update is rejected.

**Role checks across tables use a trigger.** "Only a TEACHER may teach an offering" refers
to another table, which a CHECK constraint cannot do. The `enforce_user_role` trigger
handles it. Known limitation: changing a user's role afterwards is not re-validated.

**Roles stored as `VARCHAR` + `CHECK`, not a PostgreSQL `ENUM`.** Both enforce valid values;
the VARCHAR form maps directly to a Java enum with `@Enumerated(EnumType.STRING)` and is
easier to extend with a migration.

**What is deliberately not a constraint.** Room capacity vs. section strength, LAB slots in
LAB rooms, daily teaching-hour limits and lunch breaks are rules the timetable engine
(Milestone 2) checks, because real timetables have legitimate exceptions to them.

## Planned tables (later migrations)

| Migration | Milestone | Tables | Key constraint ideas |
|-----------|-----------|--------|----------------------|
| V2 (done) | 2 | `period_slots`, `teacher_unavailability`, `timetable_runs`, `room_bookings`; `source`/`locked` on slots | exclusion per room and date for bookings; see `docs/concurrency.md` |
| V5 (done) | 5 | `feedback`, `sus_responses` | one SUS response per user; answers checked to be ten values from 1 to 5 |
| later | — | `equipment_claims` | declared max claim per lab session for the Banker's allocator |
| V3 (done) | 3 | `class_sessions`, `student_devices`, `attendance_records`, `attendance_attempts` | one record per student per session; one device per student and per session; one open session per offering and per teacher; see `docs/attendance.md` |
| V4 (done) | 4 | `stored_files`, `materials`, `assignments`, `submissions` | one submission per student per assignment; `version` for optimistic locking on grades; marks ≤ maximum (trigger); partial index on waiting submissions |

Weekly slots and dated bookings live in different tables, so no single constraint can stop
a booking that clashes with a weekly class. V2 resolves this with a row lock on the room;
see `docs/concurrency.md`.
