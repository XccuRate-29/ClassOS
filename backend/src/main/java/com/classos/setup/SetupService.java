package com.classos.setup;

import com.classos.common.ApiException;
import com.classos.setup.SetupDtos.*;
import com.classos.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * Reference data an admin maintains: departments, terms and their period grid, rooms,
 * courses, sections and enrolment, course offerings, teacher unavailability.
 *
 * Validation here exists for clear messages; the schema's constraints still decide.
 * For example, two sections with the same name in a term are stopped by uq_section_name
 * even if this code had a bug, and GlobalExceptionHandler explains it.
 */
@Service
public class SetupService {

    private final JdbcTemplate jdbc;

    public SetupService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " not found.");
    }

    private long id(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        if (v == null) throw new IllegalStateException("insert returned no id");
        return v;
    }

    // ------------------------------------------------------------------ departments

    public List<DepartmentView> departments() {
        return jdbc.query("SELECT id, code, name FROM departments ORDER BY code",
                (rs, i) -> new DepartmentView(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    @Transactional
    public DepartmentView createDepartment(DepartmentRequest r) {
        long id = id("INSERT INTO departments (code, name) VALUES (?, ?) RETURNING id",
                r.code().trim().toUpperCase(Locale.ROOT), r.name().trim());
        return new DepartmentView(id, r.code().trim().toUpperCase(Locale.ROOT), r.name().trim());
    }

    @Transactional
    public DepartmentView updateDepartment(long id, DepartmentRequest r) {
        if (jdbc.update("UPDATE departments SET code = ?, name = ? WHERE id = ?",
                r.code().trim().toUpperCase(Locale.ROOT), r.name().trim(), id) == 0) throw notFound("Department");
        return new DepartmentView(id, r.code().trim().toUpperCase(Locale.ROOT), r.name().trim());
    }

    // ------------------------------------------------------------------ terms and periods

    public List<TermView> terms() {
        Map<Long, List<PeriodView>> grids = new HashMap<>();
        jdbc.query("SELECT term_id, period_no, start_time, end_time, break_after FROM period_slots ORDER BY term_id, period_no",
                rs -> {
                    grids.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new PeriodView(rs.getInt(2),
                            rs.getObject(3, LocalTime.class), rs.getObject(4, LocalTime.class), rs.getBoolean(5)));
                });
        return jdbc.query("""
                SELECT id, name, starts_on, ends_on, working_days, is_current
                FROM academic_terms ORDER BY starts_on DESC""",
                (rs, i) -> new TermView(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                        rs.getObject(4, LocalDate.class), rs.getInt(5), rs.getBoolean(6),
                        grids.getOrDefault(rs.getLong(1), List.of())));
    }

    private TermView term(long id) {
        return terms().stream().filter(t -> t.id() == id).findFirst().orElseThrow(() -> notFound("Term"));
    }

    private static void checkDates(TermRequest r) {
        if (!r.endsOn().isAfter(r.startsOn())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The term must end after it starts.");
        }
    }

    @Transactional
    public TermView createTerm(TermRequest r) {
        checkDates(r);
        long id = id("""
                INSERT INTO academic_terms (name, starts_on, ends_on, working_days)
                VALUES (?, ?, ?, ?) RETURNING id""", r.name().trim(), r.startsOn(), r.endsOn(), r.workingDays());
        return term(id);
    }

    @Transactional
    public TermView updateTerm(long id, TermRequest r) {
        checkDates(r);
        if (jdbc.update("UPDATE academic_terms SET name = ?, starts_on = ?, ends_on = ?, working_days = ? WHERE id = ?",
                r.name().trim(), r.startsOn(), r.endsOn(), r.workingDays(), id) == 0) throw notFound("Term");
        return term(id);
    }

    /**
     * Two statements, in this order: the partial unique index allows only one current
     * term, and it is checked after each statement, so the old one must be cleared first.
     */
    @Transactional
    public TermView makeCurrent(long id) {
        jdbc.update("UPDATE academic_terms SET is_current = FALSE WHERE is_current AND id <> ?", id);
        if (jdbc.update("UPDATE academic_terms SET is_current = TRUE WHERE id = ?", id) == 0) throw notFound("Term");
        return term(id);
    }

    /** Replaces the whole grid. Periods are numbered by start time; the last never has a break after it. */
    @Transactional
    public TermView replacePeriods(long termId, PeriodGridRequest r) {
        term(termId);  // 404 if missing
        List<PeriodRequest> sorted = new ArrayList<>(r.periods());
        sorted.sort(Comparator.comparing(PeriodRequest::startTime));
        for (int i = 0; i < sorted.size(); i++) {
            PeriodRequest p = sorted.get(i);
            if (!p.endTime().isAfter(p.startTime())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Period " + (i + 1) + " must end after it starts.");
            }
            if (i > 0 && p.startTime().isBefore(sorted.get(i - 1).endTime())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Periods " + i + " and " + (i + 1) + " overlap.");
            }
        }
        jdbc.update("DELETE FROM period_slots WHERE term_id = ?", termId);
        for (int i = 0; i < sorted.size(); i++) {
            PeriodRequest p = sorted.get(i);
            jdbc.update("INSERT INTO period_slots (term_id, period_no, start_time, end_time, break_after) VALUES (?, ?, ?, ?, ?)",
                    termId, i + 1, p.startTime(), p.endTime(), p.breakAfter() && i < sorted.size() - 1);
        }
        return term(termId);
    }

    // ------------------------------------------------------------------ rooms

    public List<RoomView> rooms() {
        return jdbc.query("SELECT id, code, building, capacity, room_type, active FROM rooms ORDER BY room_type, code",
                (rs, i) -> new RoomView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getBoolean(6)));
    }

    @Transactional
    public RoomView createRoom(RoomRequest r) {
        long id = id("INSERT INTO rooms (code, building, capacity, room_type, active) VALUES (?, ?, ?, ?, ?) RETURNING id",
                r.code().trim().toUpperCase(Locale.ROOT), r.building().trim(), r.capacity(), r.roomType(),
                r.active() == null || r.active());
        return room(id);
    }

    /** Rooms are deactivated rather than deleted, because old timetables and bookings refer to them. */
    @Transactional
    public RoomView updateRoom(long id, RoomRequest r) {
        if (jdbc.update("UPDATE rooms SET code = ?, building = ?, capacity = ?, room_type = ?, active = ? WHERE id = ?",
                r.code().trim().toUpperCase(Locale.ROOT), r.building().trim(), r.capacity(), r.roomType(),
                r.active() == null || r.active(), id) == 0) throw notFound("Room");
        return room(id);
    }

    private RoomView room(long id) {
        return rooms().stream().filter(x -> x.id() == id).findFirst().orElseThrow(() -> notFound("Room"));
    }

    // ------------------------------------------------------------------ courses

    private static final String COURSE_SELECT = """
            SELECT c.id, c.department_id, d.code, c.code, c.title, c.credits, c.lecture_hours_per_week, c.lab_hours_per_week
            FROM courses c JOIN departments d ON d.id = c.department_id""";

    public List<CourseView> courses() {
        return jdbc.query(COURSE_SELECT + " ORDER BY c.code",
                (rs, i) -> new CourseView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8)));
    }

    private static void checkHours(CourseRequest r) {
        if (r.lectureHoursPerWeek() + r.labHoursPerWeek() == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A course needs at least one lecture or lab hour a week.");
        }
    }

    @Transactional
    public CourseView createCourse(CourseRequest r) {
        checkHours(r);
        long id = id("""
                INSERT INTO courses (department_id, code, title, credits, lecture_hours_per_week, lab_hours_per_week)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                r.departmentId(), r.code().trim().toUpperCase(Locale.ROOT), r.title().trim(), r.credits(),
                r.lectureHoursPerWeek(), r.labHoursPerWeek());
        return course(id);
    }

    /** Changing weekly hours affects the next timetable generation, not slots already placed. */
    @Transactional
    public CourseView updateCourse(long id, CourseRequest r) {
        checkHours(r);
        if (jdbc.update("""
                UPDATE courses SET department_id = ?, code = ?, title = ?, credits = ?,
                       lecture_hours_per_week = ?, lab_hours_per_week = ? WHERE id = ?""",
                r.departmentId(), r.code().trim().toUpperCase(Locale.ROOT), r.title().trim(), r.credits(),
                r.lectureHoursPerWeek(), r.labHoursPerWeek(), id) == 0) throw notFound("Course");
        return course(id);
    }

    private CourseView course(long id) {
        List<CourseView> found = jdbc.query(COURSE_SELECT + " WHERE c.id = ?",
                (rs, i) -> new CourseView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getInt(8)), id);
        if (found.isEmpty()) throw notFound("Course");
        return found.get(0);
    }

    // ------------------------------------------------------------------ sections

    private static final String SECTION_SELECT = """
            SELECT s.id, s.term_id, s.department_id, d.code, s.name, s.semester, s.strength,
                   (SELECT count(*) FROM section_enrollments e WHERE e.section_id = s.id) AS enrolled
            FROM sections s JOIN departments d ON d.id = s.department_id""";

    private static SectionView sectionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SectionView(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getInt(6), rs.getInt(7), rs.getInt(8));
    }

    public List<SectionView> sections(long termId) {
        return jdbc.query(SECTION_SELECT + " WHERE s.term_id = ? ORDER BY s.semester, s.name",
                (rs, i) -> sectionRow(rs), termId);
    }

    private SectionView section(long id) {
        List<SectionView> found = jdbc.query(SECTION_SELECT + " WHERE s.id = ?", (rs, i) -> sectionRow(rs), id);
        if (found.isEmpty()) throw notFound("Section");
        return found.get(0);
    }

    @Transactional
    public SectionView createSection(SectionRequest r) {
        long id = id("""
                INSERT INTO sections (term_id, department_id, name, semester, strength)
                VALUES (?, ?, ?, ?, ?) RETURNING id""",
                r.termId(), r.departmentId(), r.name().trim().toUpperCase(Locale.ROOT), r.semester(), r.strength());
        return section(id);
    }

    /** Term and department stay fixed: offerings and enrolments depend on them. */
    @Transactional
    public SectionView updateSection(long id, SectionRequest r) {
        if (jdbc.update("UPDATE sections SET name = ?, semester = ?, strength = ? WHERE id = ?",
                r.name().trim().toUpperCase(Locale.ROOT), r.semester(), r.strength(), id) == 0) throw notFound("Section");
        return section(id);
    }

    /**
     * Enrolments would be deleted silently by ON DELETE CASCADE, so a section with
     * students is refused here. Offerings block deletion through their foreign key.
     */
    @Transactional
    public void deleteSection(long id) {
        if (section(id).enrolled() > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Remove this section's students first.");
        }
        jdbc.update("DELETE FROM sections WHERE id = ?", id);
    }

    public List<StudentView> students(long sectionId) {
        section(sectionId);
        return jdbc.query("""
                SELECT u.id, u.full_name, u.email FROM section_enrollments e JOIN users u ON u.id = e.student_id
                WHERE e.section_id = ? ORDER BY u.full_name""",
                (rs, i) -> new StudentView(rs.getLong(1), rs.getString(2), rs.getString(3)), sectionId);
    }

    @Transactional
    public EnrollResult enroll(long sectionId, EnrollRequest r) {
        SectionView s = section(sectionId);
        int added = 0;
        List<String> already = new ArrayList<>(), other = new ArrayList<>(), missing = new ArrayList<>(),
                notStudents = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : r.emails()) {
            String email = UserService.normalizeEmail(raw);
            if (email.isEmpty() || !seen.add(email)) continue;
            List<Object[]> user = jdbc.query("SELECT id, role FROM users WHERE email = ?",
                    (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2)}, email);
            if (user.isEmpty()) { missing.add(email); continue; }
            long userId = (Long) user.get(0)[0];
            if (!"STUDENT".equals(user.get(0)[1])) { notStudents.add(email); continue; }
            List<Long> current = jdbc.query("SELECT section_id FROM section_enrollments WHERE term_id = ? AND student_id = ?",
                    (rs, i) -> rs.getLong(1), s.termId(), userId);
            if (!current.isEmpty()) {
                if (current.get(0) == sectionId) already.add(email); else other.add(email);
                continue;
            }
            jdbc.update("INSERT INTO section_enrollments (section_id, term_id, student_id) VALUES (?, ?, ?)",
                    sectionId, s.termId(), userId);
            added++;
        }
        return new EnrollResult(added, already, other, missing, notStudents);
    }

    @Transactional
    public void unenroll(long sectionId, long studentId) {
        if (jdbc.update("DELETE FROM section_enrollments WHERE section_id = ? AND student_id = ?", sectionId, studentId) == 0) {
            throw notFound("Enrolment");
        }
    }

    // ------------------------------------------------------------------ offerings

    private static final String OFFERING_SELECT = """
            SELECT o.id, c.id, c.code, c.title, s.id, s.name, u.id, u.full_name
            FROM course_offerings o
            JOIN courses c ON c.id = o.course_id
            JOIN sections s ON s.id = o.section_id
            JOIN users u ON u.id = o.teacher_id""";

    private static OfferingView offeringRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OfferingView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                rs.getString(6), rs.getLong(7), rs.getString(8));
    }

    public List<OfferingView> offerings(long termId) {
        return jdbc.query(OFFERING_SELECT + " WHERE o.term_id = ? ORDER BY s.name, c.code", (rs, i) -> offeringRow(rs), termId);
    }

    private OfferingView offering(long id) {
        List<OfferingView> found = jdbc.query(OFFERING_SELECT + " WHERE o.id = ?", (rs, i) -> offeringRow(rs), id);
        if (found.isEmpty()) throw notFound("Offering");
        return found.get(0);
    }

    public List<TeacherView> teachers() {
        return jdbc.query("SELECT id, full_name, email FROM users WHERE role = 'TEACHER' AND active ORDER BY full_name",
                (rs, i) -> new TeacherView(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    @Transactional
    public OfferingView createOffering(OfferingRequest r) {
        long id = id("INSERT INTO course_offerings (term_id, course_id, section_id, teacher_id) VALUES (?, ?, ?, ?) RETURNING id",
                r.termId(), r.courseId(), r.sectionId(), r.teacherId());
        return offering(id);
    }

    /**
     * ON UPDATE CASCADE copies the new teacher into every timetable slot of this offering,
     * and the no_teacher_overlap constraint re-checks them: if the new teacher is busy at
     * any of those times, the whole change is rejected with 409.
     */
    @Transactional
    public OfferingView changeTeacher(long id, TeacherChangeRequest r) {
        if (jdbc.update("UPDATE course_offerings SET teacher_id = ? WHERE id = ?", r.teacherId(), id) == 0) {
            throw notFound("Offering");
        }
        return offering(id);
    }

    /** Timetable slots of the offering go with it (ON DELETE CASCADE); attendance history blocks deletion. */
    @Transactional
    public void deleteOffering(long id) {
        if (jdbc.update("DELETE FROM course_offerings WHERE id = ?", id) == 0) throw notFound("Offering");
    }

    // ------------------------------------------------------------------ teacher unavailability

    public List<UnavailabilityView> unavailability(long termId) {
        return jdbc.query("""
                SELECT ua.id, ua.teacher_id, u.full_name, ua.day_of_week, ua.start_time, ua.end_time, ua.reason
                FROM teacher_unavailability ua JOIN users u ON u.id = ua.teacher_id
                WHERE ua.term_id = ? ORDER BY u.full_name, ua.day_of_week, ua.start_time""",
                (rs, i) -> new UnavailabilityView(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                        rs.getObject(5, LocalTime.class), rs.getObject(6, LocalTime.class), rs.getString(7)),
                termId);
    }

    @Transactional
    public void addUnavailability(UnavailabilityRequest r) {
        if (!r.endTime().isAfter(r.startTime())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "End time must be after start time.");
        }
        jdbc.update("""
                INSERT INTO teacher_unavailability (term_id, teacher_id, day_of_week, start_time, end_time, reason)
                VALUES (?, ?, ?, ?, ?, ?)""",
                r.termId(), r.teacherId(), r.dayOfWeek(), r.startTime(), r.endTime(),
                r.reason() == null || r.reason().isBlank() ? null : r.reason().trim());
    }

    @Transactional
    public void deleteUnavailability(long id) {
        if (jdbc.update("DELETE FROM teacher_unavailability WHERE id = ?", id) == 0) throw notFound("Unavailable time");
    }
}
