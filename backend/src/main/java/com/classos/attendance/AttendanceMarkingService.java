package com.classos.attendance;

import com.classos.attendance.AttendanceDtos.*;
import com.classos.attendance.AttendanceSessionService.SessionRow;
import com.classos.common.ApiException;
import com.classos.config.ClassosProperties;
import com.classos.timetable.TimetableDtos.TermInfo;
import com.classos.timetable.TimetableService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A student marking themselves present. Checks run cheapest first; each one is a
 * separate anti-proxy layer:
 *
 *   1. device id present           (every request must identify its phone)
 *   2. campus network              (optional IP-range check)
 *   3. attempt limit               (stops guessing the 6-digit code)
 *   4. session open, student enrolled in its section
 *   5. code valid for this session and this 10-second window
 *   6. device binding              (one phone per student, one student per phone)
 *   7. insert the record           (database: one record per student, one per device per session)
 *
 * Burst safety: 60+ students marking within seconds need no application locks.
 * The insert is idempotent (ON CONFLICT DO NOTHING), the session row is only read
 * FOR SHARE, and codes are computed rather than looked up.
 *
 * Every attempt is logged in its own transaction, so a rejected attempt is still
 * recorded even though the marking transaction rolled back.
 */
@Service
public class AttendanceMarkingService {

    private final JdbcTemplate jdbc;
    private final AttendanceCodes codes;
    private final CidrMatcher campusNetwork;
    private final Clock clock;
    private final AttendanceSessionService sessions;
    private final TimetableService timetable;
    private final TransactionTemplate tx;
    private final int maxFailedAttempts;
    private final int minimumPercent;

    public AttendanceMarkingService(JdbcTemplate jdbc, AttendanceCodes codes, CidrMatcher campusNetwork,
                                    Clock campusClock, AttendanceSessionService sessions, TimetableService timetable,
                                    PlatformTransactionManager txManager, ClassosProperties props) {
        this.jdbc = jdbc;
        this.codes = codes;
        this.campusNetwork = campusNetwork;
        this.clock = campusClock;
        this.sessions = sessions;
        this.timetable = timetable;
        this.tx = new TransactionTemplate(txManager);
        this.maxFailedAttempts = props.attendance().maxFailedAttempts();
        this.minimumPercent = props.attendance().minimumPercent();
    }

    public MarkResponse mark(long studentId, MarkRequest req, String rawDeviceId, String clientIp) {
        String device = DeviceIds.hash(rawDeviceId);
        MarkOutcome outcome;
        if (device == null) {
            outcome = MarkOutcome.DEVICE_MISSING;
        } else if (!campusNetwork.allows(clientIp)) {
            outcome = MarkOutcome.NETWORK_BLOCKED;
        } else if (failedAttempts(req.sessionId(), studentId) >= maxFailedAttempts) {
            outcome = MarkOutcome.TOO_MANY_ATTEMPTS;
        } else {
            try {
                outcome = tx.execute(status -> markInTransaction(studentId, req, device, clientIp));
            } catch (DataIntegrityViolationException e) {
                // Lost a race on a unique constraint (e.g. two students on one phone at the same instant).
                String msg = String.valueOf(e.getMostSpecificCause().getMessage());
                outcome = msg.contains("device") ? MarkOutcome.DEVICE_IN_USE : MarkOutcome.ALREADY_MARKED;
            }
        }
        logAttempt(req.sessionId(), studentId, outcome, req.method(), device, clientIp);
        return new MarkResponse(outcome.name(), outcome.message, req.sessionId());
    }

    private MarkOutcome markInTransaction(long studentId, MarkRequest req, String device, String clientIp) {
        // FOR SHARE: a reader. Many marks proceed together; close() (FOR UPDATE) waits for them.
        SessionRow s = sessions.load(req.sessionId(), " FOR SHARE");
        if (s == null) return MarkOutcome.NOT_FOUND;
        Instant now = clock.instant();
        if (!s.acceptingMarks(now)) return MarkOutcome.SESSION_CLOSED;

        Integer enrolled = jdbc.queryForObject(
                "SELECT count(*) FROM section_enrollments WHERE section_id = ? AND term_id = ? AND student_id = ?",
                Integer.class, s.sectionId(), s.termId(), studentId);
        if (enrolled == null || enrolled == 0) return MarkOutcome.NOT_ENROLLED;

        switch (codes.check(s.id(), s.nonce(), req.code().trim(), now)) {
            case WRONG -> { return MarkOutcome.WRONG_CODE; }
            case EXPIRED -> { return MarkOutcome.EXPIRED_CODE; }
            case VALID -> { }
        }

        // First successful mark registers the phone. ON CONFLICT DO NOTHING without a target
        // also covers "this phone already belongs to another student" (the UNIQUE on device_hash).
        String registered = registeredDevice(studentId);
        if (registered == null) {
            jdbc.update("INSERT INTO student_devices (student_id, device_hash) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    studentId, device);
            registered = registeredDevice(studentId);
            if (registered == null) return MarkOutcome.DEVICE_IN_USE;
        }
        if (!registered.equals(device)) return MarkOutcome.DEVICE_MISMATCH;

        int inserted = jdbc.update("""
                INSERT INTO attendance_records (session_id, student_id, status, method, device_hash, client_ip)
                VALUES (?, ?, 'PRESENT', ?, ?, CAST(? AS inet))
                ON CONFLICT (session_id, student_id) DO NOTHING""",
                s.id(), studentId, req.method().name(), device, validIp(clientIp));
        return inserted == 1 ? MarkOutcome.ACCEPTED : MarkOutcome.ALREADY_MARKED;
    }

    private String registeredDevice(long studentId) {
        List<String> found = jdbc.query("SELECT device_hash FROM student_devices WHERE student_id = ?",
                (rs, i) -> rs.getString(1), studentId);
        return found.isEmpty() ? null : found.get(0);
    }

    private int failedAttempts(long sessionId, long studentId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM attendance_attempts
                WHERE session_id = ? AND student_id = ? AND outcome IN ('WRONG_CODE', 'EXPIRED_CODE')""",
                Integer.class, sessionId, studentId);
        return n == null ? 0 : n;
    }

    /** Own transaction; skipped silently if the session does not exist (no foreign-key error). */
    private void logAttempt(long sessionId, long studentId, MarkOutcome outcome, Method method,
                            String device, String clientIp) {
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO attendance_attempts (session_id, student_id, outcome, method, device_hash, client_ip)
                SELECT ?, ?, ?, ?, ?, CAST(? AS inet)
                WHERE EXISTS (SELECT 1 FROM class_sessions WHERE id = ?)""",
                sessionId, studentId, outcome.name(), method == null ? null : method.name(), device,
                validIp(clientIp), sessionId));
    }

    /** Only IP literals reach the inet cast; anything else is stored as null. */
    private static String validIp(String ip) {
        return ip != null && ip.matches("[0-9a-fA-F:.]{2,45}") ? ip : null;
    }

    // ------------------------------------------------------------------ student views

    public List<OpenSession> openSessions(long studentId) {
        return jdbc.query("""
                SELECT cs.id, c.code, c.title, u.full_name, r.code, cs.auto_close_at,
                       EXISTS (SELECT 1 FROM attendance_records ar
                               WHERE ar.session_id = cs.id AND ar.student_id = ?) AS marked
                FROM class_sessions cs
                JOIN section_enrollments e ON e.section_id = cs.section_id AND e.term_id = cs.term_id
                JOIN course_offerings o ON o.id = cs.offering_id
                JOIN courses c ON c.id = o.course_id
                JOIN users u ON u.id = cs.teacher_id
                LEFT JOIN rooms r ON r.id = cs.room_id
                WHERE e.student_id = ? AND cs.status = 'OPEN' AND cs.auto_close_at > now()
                ORDER BY cs.opened_at""",
                (rs, i) -> new OpenSession(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, OffsetDateTime.class), rs.getBoolean(7)),
                studentId, studentId);
    }

    /** Per course: classes held (closed sessions) and attended, against the minimum percentage. */
    public StudentSummary summary(long studentId) {
        TermInfo term = timetable.resolveTerm(null);
        List<CourseAttendance> courses = jdbc.query("""
                SELECT o.id, c.code, c.title,
                       count(cs.id) AS held,
                       count(ar.student_id) FILTER (WHERE ar.status = 'PRESENT') AS present
                FROM section_enrollments e
                JOIN course_offerings o ON o.section_id = e.section_id AND o.term_id = e.term_id
                JOIN courses c ON c.id = o.course_id
                LEFT JOIN class_sessions cs ON cs.offering_id = o.id AND cs.status = 'CLOSED'
                LEFT JOIN attendance_records ar ON ar.session_id = cs.id AND ar.student_id = e.student_id
                WHERE e.student_id = ? AND e.term_id = ?
                GROUP BY o.id, c.code, c.title
                ORDER BY c.code""",
                (rs, i) -> {
                    int held = rs.getInt(4), present = rs.getInt(5);
                    Double percent = held == 0 ? null : Math.round(1000.0 * present / held) / 10.0;
                    return new CourseAttendance(rs.getLong(1), rs.getString(2), rs.getString(3), held, present,
                            percent, percent != null && percent < minimumPercent);
                },
                studentId, term.id());
        return new StudentSummary(minimumPercent, courses);
    }

    public DeviceStatus deviceStatus(long studentId, String rawDeviceId) {
        String registered = registeredDevice(studentId);
        String mine = DeviceIds.hash(rawDeviceId);
        return new DeviceStatus(registered != null, registered != null && registered.equals(mine));
    }

    /** Admin action when a student changes phones. */
    public void resetDevice(long studentId) {
        if (jdbc.update("DELETE FROM student_devices WHERE student_id = ?", studentId) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "This student has no registered device.");
        }
    }
}
