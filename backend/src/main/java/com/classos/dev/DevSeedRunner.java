package com.classos.dev;

import com.classos.config.ClassosProperties;
import com.classos.user.Role;
import com.classos.user.UserDtos.CreateUserRequest;
import com.classos.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Sample department for development and demos. Runs only when DEV_SEED_ENABLED=true,
 * and only once (skips if the CSE department already exists).
 *
 * All names and data are fictional. Every seeded account uses DEV_SEED_PASSWORD;
 * never enable this on a server real people use.
 */
@Component
@Order(2)  // after AdminBootstrap
@ConditionalOnProperty(name = "classos.dev-seed.enabled", havingValue = "true")
public class DevSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevSeedRunner.class);

    private final JdbcTemplate jdbc;
    private final UserService users;
    private final ClassosProperties props;

    public DevSeedRunner(JdbcTemplate jdbc, UserService users, ClassosProperties props) {
        this.jdbc = jdbc;
        this.users = users;
        this.props = props;
    }

    private record CourseSpec(String code, String title, int credits, int lectures, int labHours) {}

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM departments WHERE code = 'CSE'", Integer.class);
        if (exists != null && exists > 0) {
            log.info("Dev seed: CSE department already exists, skipping.");
            return;
        }
        String password = props.devSeed().password();
        if (password == null || password.length() < 10) {
            throw new IllegalStateException("DEV_SEED_PASSWORD must be at least 10 characters when DEV_SEED_ENABLED=true");
        }

        long dept = id("INSERT INTO departments (code, name) VALUES ('CSE', 'Computer Science and Engineering') RETURNING id");

        jdbc.update("UPDATE academic_terms SET is_current = FALSE WHERE is_current");
        long term = id("""
                INSERT INTO academic_terms (name, starts_on, ends_on, is_current, working_days)
                VALUES ('Odd 2026', DATE '2026-07-15', DATE '2026-12-15', TRUE, 5) RETURNING id""");

        // 8 periods, lunch after period 4.
        String[][] grid = {{"09:00", "09:55"}, {"09:55", "10:50"}, {"10:50", "11:45"}, {"11:45", "12:40"},
                           {"13:30", "14:25"}, {"14:25", "15:20"}, {"15:20", "16:15"}, {"16:15", "17:10"}};
        for (int p = 0; p < grid.length; p++) {
            jdbc.update("INSERT INTO period_slots (term_id, period_no, start_time, end_time, break_after) VALUES (?, ?, ?, ?, ?)",
                    term, p + 1, LocalTime.parse(grid[p][0]), LocalTime.parse(grid[p][1]), p == 3);
        }

        for (int r = 1; r <= 6; r++) {
            jdbc.update("INSERT INTO rooms (code, building, capacity, room_type) VALUES (?, 'Academic Block A', 70, 'CLASSROOM')",
                    "A-" + (100 + r));
        }
        jdbc.update("INSERT INTO rooms (code, building, capacity, room_type) VALUES ('SEM-1', 'Academic Block A', 120, 'SEMINAR_HALL')");
        for (int r = 1; r <= 3; r++) {
            jdbc.update("INSERT INTO rooms (code, building, capacity, room_type) VALUES (?, 'CSIT Block', 65, 'LAB')", "LAB-" + r);
        }

        String[] teacherNames = {"Anita Rawat", "Vikram Negi", "Priya Bisht", "Rahul Joshi",
                                 "Meera Pant", "Sanjay Kumar", "Neha Bhatt", "Arjun Rana"};
        List<Long> teachers = new ArrayList<>();
        for (String name : teacherNames) teachers.add(user(name, Role.TEACHER, dept, password));
        user("Deepak Thapa", Role.LAB_ASSISTANT, dept, password);

        List<CourseSpec> sem3 = List.of(
                new CourseSpec("TCS-301", "Data Structures", 4, 3, 2),
                new CourseSpec("TCS-302", "Operating Systems", 4, 3, 2),
                new CourseSpec("TCS-303", "Database Management Systems", 4, 3, 2),
                new CourseSpec("TMA-301", "Discrete Mathematics", 4, 4, 0),
                new CourseSpec("THS-301", "Professional Communication", 2, 2, 0));
        List<CourseSpec> sem5 = List.of(
                new CourseSpec("TCS-501", "Computer Networks", 4, 3, 2),
                new CourseSpec("TCS-502", "Compiler Design", 3, 3, 0),
                new CourseSpec("TCS-503", "Machine Learning", 4, 3, 2),
                new CourseSpec("TCS-504", "Software Engineering", 3, 3, 0),
                new CourseSpec("TCS-505", "Web Technologies", 3, 2, 2));

        List<Long> sem3Courses = courses(dept, sem3);
        List<Long> sem5Courses = courses(dept, sem5);

        int teacherTurn = 0;
        String[][] sections = {{"CSE-3A", "3"}, {"CSE-3B", "3"}, {"CSE-3C", "3"}, {"CSE-5A", "5"}, {"CSE-5B", "5"}};
        for (String[] s : sections) {
            int semester = Integer.parseInt(s[1]);
            long section = id("INSERT INTO sections (term_id, department_id, name, semester, strength) VALUES (?, ?, ?, ?, 60) RETURNING id",
                    term, dept, s[0], semester);
            for (long course : semester == 3 ? sem3Courses : sem5Courses) {
                jdbc.update("INSERT INTO course_offerings (term_id, course_id, section_id, teacher_id) VALUES (?, ?, ?, ?)",
                        term, course, section, teachers.get(teacherTurn++ % teachers.size()));
            }
            // Two sample students per section.
            for (int k = 1; k <= 2; k++) {
                long student = user("Student " + s[0] + "-" + k, Role.STUDENT, dept, password);
                jdbc.update("INSERT INTO section_enrollments (section_id, term_id, student_id) VALUES (?, ?, ?)",
                        section, term, student);
            }
        }

        // One teacher is unavailable on Monday mornings: the solver must work around it.
        jdbc.update("""
                INSERT INTO teacher_unavailability (term_id, teacher_id, day_of_week, start_time, end_time, reason)
                VALUES (?, ?, 1, '09:00', '12:40', 'Department meeting')""", term, teachers.get(0));

        log.info("Dev seed: created CSE department, term 'Odd 2026', 10 rooms, 9 staff, 10 students. "
                + "Sign in as anita.rawat@classos.local (teacher) with DEV_SEED_PASSWORD.");
    }

    private List<Long> courses(long dept, List<CourseSpec> specs) {
        List<Long> ids = new ArrayList<>();
        for (CourseSpec c : specs) {
            ids.add(id("""
                    INSERT INTO courses (department_id, code, title, credits, lecture_hours_per_week, lab_hours_per_week)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                    dept, c.code(), c.title(), c.credits(), c.lectures(), c.labHours()));
        }
        return ids;
    }

    private long user(String fullName, Role role, long dept, String password) {
        String email = fullName.toLowerCase().replace(' ', '.') + "@classos.local";
        return users.create(new CreateUserRequest(email, password, fullName, role, dept)).getId();
    }

    private long id(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        if (v == null) throw new IllegalStateException("insert returned no id");
        return v;
    }
}
