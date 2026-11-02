package com.classos;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** Small helpers to insert test data with unique names, so tests never collide. */
public class Fixtures {

    private final JdbcTemplate jdbc;
    public final String s = UUID.randomUUID().toString().substring(0, 8);
    public final long dept;

    public Fixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.dept = id("INSERT INTO departments (code, name) VALUES (?, ?) RETURNING id", "D" + s, "Dept " + s);
    }

    public long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    public long term(LocalDate from, LocalDate to, int workingDays) {
        return id("INSERT INTO academic_terms (name, starts_on, ends_on, working_days) VALUES (?, ?, ?, ?) RETURNING id",
                "T" + s, from, to, workingDays);
    }

    public void period(long term, int no, String start, String end, boolean breakAfter) {
        jdbc.update("INSERT INTO period_slots (term_id, period_no, start_time, end_time, break_after) VALUES (?, ?, ?, ?, ?)",
                term, no, LocalTime.parse(start), LocalTime.parse(end), breakAfter);
    }

    public long user(String role) {
        return id("INSERT INTO users (email, password_hash, full_name, role) VALUES (?, 'x', ?, ?) RETURNING id",
                role.toLowerCase() + UUID.randomUUID().toString().substring(0, 8) + "@test.local", role + " " + s, role);
    }

    public long section(long term, String name, int strength) {
        return id("INSERT INTO sections (term_id, department_id, name, semester, strength) VALUES (?, ?, ?, 3, ?) RETURNING id",
                term, dept, name + s, strength);
    }

    public long course(String code, int lectures, int labHours) {
        return id("""
                INSERT INTO courses (department_id, code, title, credits, lecture_hours_per_week, lab_hours_per_week)
                VALUES (?, ?, 'Course', 4, ?, ?) RETURNING id""", dept, code + s, lectures, labHours);
    }

    public long offering(long term, long course, long section, long teacher) {
        return id("INSERT INTO course_offerings (term_id, course_id, section_id, teacher_id) VALUES (?, ?, ?, ?) RETURNING id",
                term, course, section, teacher);
    }

    public long room(String code, int capacity, String type) {
        return id("INSERT INTO rooms (code, building, capacity, room_type) VALUES (?, 'Test', ?, ?) RETURNING id",
                code + s, capacity, type);
    }
}
