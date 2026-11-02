package com.classos.attendance;

import com.classos.attendance.AttendanceDtos.*;
import com.classos.common.ApiException;
import com.classos.config.ClassosProperties;
import com.classos.timetable.TimetableDtos.TermInfo;
import com.classos.timetable.TimetableService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The teacher's side of attendance: open a session, show the rotating code,
 * correct records by hand, close the session.
 *
 * Locking on the class_sessions row (readers-writers, as for timetable edits):
 *   - each student's mark takes the row FOR SHARE: many marks run in parallel
 *   - closing takes it FOR UPDATE: it waits for marks already in progress, and
 *     any mark arriving afterwards sees status CLOSED
 * So a close never interleaves with a mark: every student ends up with exactly one
 * record, PRESENT if their mark committed first, ABSENT otherwise.
 */
@Service
public class AttendanceSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final AttendanceCodes codes;
    private final Clock clock;
    private final TimetableService timetable;
    private final int sessionMinutes;

    public AttendanceSessionService(JdbcTemplate jdbc, AttendanceCodes codes, Clock campusClock,
                                    TimetableService timetable, ClassosProperties props) {
        this.jdbc = jdbc;
        this.codes = codes;
        this.clock = campusClock;
        this.timetable = timetable;
        this.sessionMinutes = props.attendance().sessionMinutes();
    }

    record SessionRow(long id, long termId, long offeringId, long sectionId, long teacherId, String status,
                      byte[] nonce, Instant autoCloseAt) {
        boolean acceptingMarks(Instant now) {
            return "OPEN".equals(status) && now.isBefore(autoCloseAt);
        }
    }

    /** lock: "", " FOR SHARE" or " FOR UPDATE". Returns null when the session does not exist. */
    SessionRow load(long sessionId, String lock) {
        List<SessionRow> rows = jdbc.query("""
                SELECT id, term_id, offering_id, section_id, teacher_id, status, code_nonce, auto_close_at
                FROM class_sessions WHERE id = ?""" + lock,
                (rs, i) -> new SessionRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getString(6), rs.getBytes(7), rs.getObject(8, OffsetDateTime.class).toInstant()),
                sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Another teacher's session is reported as missing, so its existence is not revealed. */
    private SessionRow owned(long teacherId, long sessionId, String lock) {
        SessionRow s = load(sessionId, lock);
        if (s == null || s.teacherId() != teacherId) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Attendance session not found.");
        }
        return s;
    }

    // ------------------------------------------------------------------ today

    @Transactional(readOnly = true)
    public TeacherToday today(long teacherId) {
        TermInfo term = timetable.resolveTerm(null);
        LocalDate date = LocalDate.now(clock);
        List<TodayClass> classes = jdbc.query("""
                SELECT ts.id, ts.offering_id, c.code, c.title, sec.name, r.code, ts.start_time, ts.end_time,
                       (SELECT cs.id FROM class_sessions cs
                        WHERE cs.offering_id = ts.offering_id AND cs.status = 'OPEN' AND cs.auto_close_at > now()
                        LIMIT 1) AS open_id
                FROM timetable_slots ts
                JOIN course_offerings o ON o.id = ts.offering_id
                JOIN courses c ON c.id = o.course_id
                JOIN sections sec ON sec.id = ts.section_id
                JOIN rooms r ON r.id = ts.room_id
                WHERE ts.term_id = ? AND ts.teacher_id = ? AND ts.day_of_week = ?
                ORDER BY ts.start_time""",
                (rs, i) -> new TodayClass(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, LocalTime.class),
                        rs.getObject(8, LocalTime.class), (Long) rs.getObject(9)),
                term.id(), teacherId, date.getDayOfWeek().getValue());
        List<OfferingOption> offerings = jdbc.query("""
                SELECT o.id, c.code, c.title, sec.name
                FROM course_offerings o JOIN courses c ON c.id = o.course_id JOIN sections sec ON sec.id = o.section_id
                WHERE o.term_id = ? AND o.teacher_id = ? ORDER BY sec.name, c.code""",
                (rs, i) -> new OfferingOption(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                term.id(), teacherId);
        List<Long> open = jdbc.query(
                "SELECT id FROM class_sessions WHERE teacher_id = ? AND status = 'OPEN' AND auto_close_at > now()",
                (rs, i) -> rs.getLong(1), teacherId);
        return new TeacherToday(date, classes, offerings, open.isEmpty() ? null : open.get(0));
    }

    // ------------------------------------------------------------------ start

    @Transactional
    public SessionDetail start(long teacherId, StartRequest req) {
        closeExpired();  // a session the teacher forgot to close must not block a new one

        List<long[]> offering = jdbc.query(
                "SELECT term_id, section_id, teacher_id FROM course_offerings WHERE id = ?",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)}, req.offeringId());
        if (offering.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Course offering not found.");
        long termId = offering.get(0)[0], sectionId = offering.get(0)[1];
        if (offering.get(0)[2] != teacherId) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only take attendance for courses you teach.");
        }

        Long roomId = null;
        if (req.slotId() != null) {
            List<Long> room = jdbc.query("SELECT room_id FROM timetable_slots WHERE id = ? AND offering_id = ?",
                    (rs, i) -> rs.getLong(1), req.slotId(), req.offeringId());
            if (room.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "That timetable slot is not for this course.");
            roomId = room.get(0);
        }

        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        // The partial unique indexes reject a second open session (handled as 409).
        Long id = jdbc.queryForObject("""
                INSERT INTO class_sessions (term_id, offering_id, section_id, teacher_id, slot_id, room_id,
                                            session_date, code_nonce, auto_close_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now() + make_interval(mins => ?)) RETURNING id""",
                Long.class, termId, req.offeringId(), sectionId, teacherId, req.slotId(), roomId,
                LocalDate.now(clock), nonce, sessionMinutes);
        return detailOf(id);
    }

    // ------------------------------------------------------------------ the rotating code

    /** Polled by the teacher's screen. Codes are computed, never stored. */
    @Transactional
    public CodeView code(long teacherId, long sessionId) {
        SessionRow s = owned(teacherId, sessionId, "");
        Instant now = clock.instant();
        if ("OPEN".equals(s.status()) && !s.acceptingMarks(now)) {
            closeLocked(load(sessionId, " FOR UPDATE"));  // time is up: close it now
            s = load(sessionId, "");
        }
        int[] counts = counts(s);
        if (!"OPEN".equals(s.status())) {
            return new CodeView(sessionId, null, codes.windowSeconds(), 0, now, s.status(), counts[0], counts[1],
                    s.autoCloseAt());
        }
        long window = codes.windowOf(now);
        long validFor = Duration.between(now, codes.windowEnd(window)).toMillis();
        return new CodeView(sessionId, codes.codeFor(sessionId, s.nonce(), window), codes.windowSeconds(), validFor,
                now, s.status(), counts[0], counts[1], s.autoCloseAt());
    }

    /** Used by tests and by code(): the code for the current window. */
    public String currentCode(long sessionId) {
        SessionRow s = load(sessionId, "");
        if (s == null) throw new ApiException(HttpStatus.NOT_FOUND, "Attendance session not found.");
        return codes.codeFor(sessionId, s.nonce(), codes.windowOf(clock.instant()));
    }

    private int[] counts(SessionRow s) {
        Integer present = jdbc.queryForObject(
                "SELECT count(*) FROM attendance_records WHERE session_id = ? AND status = 'PRESENT'", Integer.class, s.id());
        Integer enrolled = jdbc.queryForObject(
                "SELECT count(*) FROM section_enrollments WHERE section_id = ? AND term_id = ?",
                Integer.class, s.sectionId(), s.termId());
        return new int[]{present == null ? 0 : present, enrolled == null ? 0 : enrolled};
    }

    // ------------------------------------------------------------------ details and corrections

    @Transactional(readOnly = true)
    public SessionDetail detail(long teacherId, long sessionId) {
        owned(teacherId, sessionId, "");
        return detailOf(sessionId);
    }

    private SessionDetail detailOf(long sessionId) {
        Map<String, Object> h = jdbc.queryForMap("""
                SELECT cs.id, c.code, c.title, sec.name AS section_name, r.code AS room_code, cs.session_date,
                       cs.status, cs.opened_at, cs.auto_close_at, cs.closed_at, cs.section_id, cs.term_id
                FROM class_sessions cs
                JOIN course_offerings o ON o.id = cs.offering_id
                JOIN courses c ON c.id = o.course_id
                JOIN sections sec ON sec.id = cs.section_id
                LEFT JOIN rooms r ON r.id = cs.room_id
                WHERE cs.id = ?""", sessionId);
        long sectionId = ((Number) h.get("section_id")).longValue();
        long termId = ((Number) h.get("term_id")).longValue();

        List<StudentRow> students = jdbc.query("""
                SELECT u.id, u.full_name, u.email, ar.status, ar.method, ar.marked_at
                FROM section_enrollments e
                JOIN users u ON u.id = e.student_id
                LEFT JOIN attendance_records ar ON ar.session_id = ? AND ar.student_id = e.student_id
                WHERE e.section_id = ? AND e.term_id = ?
                ORDER BY u.full_name""",
                (rs, i) -> new StudentRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, OffsetDateTime.class)),
                sessionId, sectionId, termId);

        Map<String, Integer> rejected = new LinkedHashMap<>();
        jdbc.query("""
                SELECT outcome, count(*) FROM attendance_attempts
                WHERE session_id = ? AND outcome NOT IN ('ACCEPTED', 'ALREADY_MARKED')
                GROUP BY outcome ORDER BY count(*) DESC""",
                rs -> { rejected.put(rs.getString(1), rs.getInt(2)); }, sessionId);

        int present = (int) students.stream().filter(s -> "PRESENT".equals(s.status())).count();
        return new SessionDetail(sessionId, (String) h.get("code"), (String) h.get("title"),
                (String) h.get("section_name"), (String) h.get("room_code"),
                ((java.sql.Date) h.get("session_date")).toLocalDate(), (String) h.get("status"),
                toOffset(h.get("opened_at")), toOffset(h.get("auto_close_at")), toOffset(h.get("closed_at")),
                students.size(), present, students, rejected);
    }

    private static OffsetDateTime toOffset(Object v) {
        if (v == null) return null;
        if (v instanceof OffsetDateTime o) return o;
        return ((java.sql.Timestamp) v).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    /** The teacher's word is final: works while open and after closing (corrections). */
    @Transactional
    public SessionDetail manual(long teacherId, long sessionId, ManualRequest req) {
        SessionRow s = owned(teacherId, sessionId, " FOR SHARE");
        Integer enrolled = jdbc.queryForObject(
                "SELECT count(*) FROM section_enrollments WHERE section_id = ? AND term_id = ? AND student_id = ?",
                Integer.class, s.sectionId(), s.termId(), req.studentId());
        if (enrolled == null || enrolled == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "That student is not in this section.");
        }
        jdbc.update("""
                INSERT INTO attendance_records (session_id, student_id, status, method, marked_by)
                VALUES (?, ?, ?, 'MANUAL', ?)
                ON CONFLICT (session_id, student_id)
                DO UPDATE SET status = EXCLUDED.status, method = 'MANUAL',
                              marked_by = EXCLUDED.marked_by, marked_at = now()""",
                sessionId, req.studentId(), req.present() ? "PRESENT" : "ABSENT", teacherId);
        return detailOf(sessionId);
    }

    // ------------------------------------------------------------------ closing

    @Transactional
    public SessionDetail close(long teacherId, long sessionId) {
        SessionRow s = owned(teacherId, sessionId, " FOR UPDATE");  // the writer: waits for marks in progress
        closeLocked(s);
        return detailOf(sessionId);
    }

    /** Caller must hold the row FOR UPDATE. Marks everyone without a record ABSENT, atomically. */
    private void closeLocked(SessionRow s) {
        if (s == null || !"OPEN".equals(s.status())) return;
        jdbc.update("UPDATE class_sessions SET status = 'CLOSED', closed_at = now() WHERE id = ?", s.id());
        jdbc.update("""
                INSERT INTO attendance_records (session_id, student_id, status, method)
                SELECT ?, e.student_id, 'ABSENT', 'AUTO'
                FROM section_enrollments e
                WHERE e.section_id = ? AND e.term_id = ?
                ON CONFLICT (session_id, student_id) DO NOTHING""",
                s.id(), s.sectionId(), s.termId());
    }

    /**
     * Closes every session past its time limit. SKIP LOCKED: a session a teacher is
     * closing right now is left to that teacher instead of waiting for it.
     */
    @Transactional
    public int closeExpired() {
        List<Long> ids = jdbc.query("""
                SELECT id FROM class_sessions WHERE status = 'OPEN' AND auto_close_at <= now()
                ORDER BY id FOR UPDATE SKIP LOCKED""", (rs, i) -> rs.getLong(1));
        for (long id : ids) closeLocked(load(id, ""));
        return ids.size();
    }

    @Transactional(readOnly = true)
    public List<SessionHistoryRow> history(long teacherId) {
        return jdbc.query("""
                SELECT cs.id, cs.session_date, c.code, sec.name, cs.status,
                       count(*) FILTER (WHERE ar.status = 'PRESENT') AS present,
                       (SELECT count(*) FROM section_enrollments e
                        WHERE e.section_id = cs.section_id AND e.term_id = cs.term_id) AS enrolled
                FROM class_sessions cs
                JOIN course_offerings o ON o.id = cs.offering_id
                JOIN courses c ON c.id = o.course_id
                JOIN sections sec ON sec.id = cs.section_id
                LEFT JOIN attendance_records ar ON ar.session_id = cs.id
                WHERE cs.teacher_id = ?
                GROUP BY cs.id, cs.session_date, c.code, sec.name, cs.status
                ORDER BY cs.opened_at DESC LIMIT 50""",
                (rs, i) -> new SessionHistoryRow(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7)),
                teacherId);
    }
}
