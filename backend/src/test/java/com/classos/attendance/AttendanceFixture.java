package com.classos.attendance;

import com.classos.Fixtures;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A section with a teacher, an offering and N enrolled students, each with their own "phone". */
class AttendanceFixture {
    final long term, section, teacher, offering;
    final List<Long> students = new ArrayList<>();
    final List<String> devices = new ArrayList<>();

    AttendanceFixture(JdbcTemplate jdbc, int studentCount) {
        Fixtures f = new Fixtures(jdbc);
        term = f.term(LocalDate.now().minusDays(10), LocalDate.now().plusDays(100), 5);
        section = f.section(term, "S", Math.max(1, studentCount));
        teacher = f.user("TEACHER");
        offering = f.offering(term, f.course("C", 3, 0), section, teacher);
        for (int i = 0; i < studentCount; i++) {
            long st = f.user("STUDENT");
            jdbc.update("INSERT INTO section_enrollments (section_id, term_id, student_id) VALUES (?, ?, ?)", section, term, st);
            students.add(st);
            devices.add(UUID.randomUUID().toString());  // what the browser would generate
        }
    }
}
