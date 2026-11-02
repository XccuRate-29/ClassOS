package com.classos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Proves that the database itself rejects conflicts, independent of any Java code.
class SchemaConstraintsTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    long dept, term, sectionA, sectionB, teacher1, teacher2, student, roomX, roomY, offeringA, offeringB;

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long user(String s, String role) {
        return id("INSERT INTO users (email, password_hash, full_name, role) VALUES (?, 'x', ?, ?) RETURNING id",
                role.toLowerCase() + s + "@test.local", "User " + s, role);
    }

    String suffix;

    @BeforeEach
    void fixture() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        suffix = s;
        dept = id("INSERT INTO departments (code, name) VALUES (?, ?) RETURNING id", "D" + s, "Dept " + s);
        term = id("INSERT INTO academic_terms (name, starts_on, ends_on) VALUES (?, DATE '2026-07-01', DATE '2026-12-15') RETURNING id", "T" + s);
        sectionA = id("INSERT INTO sections (term_id, department_id, name, semester, strength) VALUES (?, ?, ?, 3, 60) RETURNING id", term, dept, "A" + s);
        sectionB = id("INSERT INTO sections (term_id, department_id, name, semester, strength) VALUES (?, ?, ?, 3, 60) RETURNING id", term, dept, "B" + s);
        teacher1 = user(s + "1", "TEACHER");
        teacher2 = user(s + "2", "TEACHER");
        student = user(s, "STUDENT");
        long course = id("INSERT INTO courses (department_id, code, title, credits, lecture_hours_per_week) VALUES (?, ?, 'Operating Systems', 4, 3) RETURNING id", dept, "C" + s);
        roomX = id("INSERT INTO rooms (code, building, capacity, room_type) VALUES (?, 'Main', 70, 'CLASSROOM') RETURNING id", "X" + s);
        roomY = id("INSERT INTO rooms (code, building, capacity, room_type) VALUES (?, 'Main', 70, 'CLASSROOM') RETURNING id", "Y" + s);
        offeringA = id("INSERT INTO course_offerings (term_id, course_id, section_id, teacher_id) VALUES (?, ?, ?, ?) RETURNING id", term, course, sectionA, teacher1);
        offeringB = id("INSERT INTO course_offerings (term_id, course_id, section_id, teacher_id) VALUES (?, ?, ?, ?) RETURNING id", term, course, sectionB, teacher2);
    }

    private void slot(long offering, long teacher, long section, long room, int day, int startHour, int endHour) {
        jdbc.update("""
                INSERT INTO timetable_slots (offering_id, term_id, teacher_id, section_id, room_id,
                                             day_of_week, start_time, end_time, slot_kind)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'LECTURE')""",
                offering, term, teacher, section, room, day,
                LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
    }

    @Test
    void sameRoomOverlappingTimeIsRejected() {
        slot(offeringA, teacher1, sectionA, roomX, 1, 9, 11);
        assertThrows(DataIntegrityViolationException.class,
                () -> slot(offeringB, teacher2, sectionB, roomX, 1, 10, 12));
    }

    @Test
    void backToBackAndOtherDaysAreAllowed() {
        slot(offeringA, teacher1, sectionA, roomX, 1, 9, 10);
        assertDoesNotThrow(() -> slot(offeringB, teacher2, sectionB, roomX, 1, 10, 11));  // half-open ranges
        assertDoesNotThrow(() -> slot(offeringB, teacher2, sectionB, roomX, 2, 9, 10));   // different day
    }

    @Test
    void teacherCannotBeInTwoRoomsAtOnce() {
        slot(offeringA, teacher1, sectionA, roomX, 3, 9, 10);
        // teacher1 also teaches a different course to section B.
        long course2 = id("INSERT INTO courses (department_id, code, title, credits, lecture_hours_per_week) VALUES (?, ?, 'DBMS', 4, 3) RETURNING id",
                dept, "K" + suffix);
        long offering2 = id("INSERT INTO course_offerings (term_id, course_id, section_id, teacher_id) VALUES (?, ?, ?, ?) RETURNING id",
                term, course2, sectionB, teacher1);
        assertThrows(DataIntegrityViolationException.class,
                () -> slot(offering2, teacher1, sectionB, roomY, 3, 9, 10));
    }

    @Test
    void slotCopiesMustMatchTheOffering() {
        // teacher2 does not teach offeringA: the composite foreign key rejects it.
        assertThrows(DataIntegrityViolationException.class,
                () -> slot(offeringA, teacher2, sectionA, roomX, 4, 9, 10));
    }

    @Test
    void reassigningTeacherIntoAClashIsRejected() {
        slot(offeringA, teacher1, sectionA, roomX, 5, 9, 10);
        slot(offeringB, teacher2, sectionB, roomY, 5, 9, 10);
        // Cascade copies teacher1 into offeringB's slots, which now clash with offeringA's.
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE course_offerings SET teacher_id = ? WHERE id = ?", teacher1, offeringB));
    }

    @Test
    void onlyTeachersCanTeachAndOnlyStudentsCanEnrol() {
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE course_offerings SET teacher_id = ? WHERE id = ?", student, offeringA));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO section_enrollments (section_id, term_id, student_id) VALUES (?, ?, ?)",
                        sectionA, term, teacher1));
        assertDoesNotThrow(
                () -> jdbc.update("INSERT INTO section_enrollments (section_id, term_id, student_id) VALUES (?, ?, ?)",
                        sectionA, term, student));
        // Same student, second section, same term: rejected.
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO section_enrollments (section_id, term_id, student_id) VALUES (?, ?, ?)",
                        sectionB, term, student));
    }
}
