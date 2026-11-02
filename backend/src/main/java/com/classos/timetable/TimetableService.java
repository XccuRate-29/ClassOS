package com.classos.timetable;

import com.classos.common.ApiException;
import com.classos.timetable.TimetableDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Conflict detection and manual timetable editing.
 *
 * Plain JDBC instead of JPA here: the queries rely on PostgreSQL range operators
 * (&&) and row locks, which are clearer in SQL than through an ORM.
 *
 * Locking order, used everywhere to prevent deadlocks (resource ordering, as in OS
 * deadlock prevention): academic_terms row first, then rooms rows in ascending id.
 *   - manual edits:   term FOR SHARE,  then the target room FOR UPDATE
 *   - generation:     term FOR UPDATE, then all rooms (ORDER BY id) FOR UPDATE
 *   - room bookings:  the room FOR UPDATE only
 * See docs/concurrency.md.
 */
@Service
public class TimetableService {

    static final String SLOT_SELECT = """
            SELECT ts.id, ts.offering_id, c.code AS course_code, c.title AS course_title,
                   ts.section_id, sec.name AS section_name, ts.teacher_id, u.full_name AS teacher_name,
                   ts.room_id, r.code AS room_code, r.room_type, ts.day_of_week, ts.start_time, ts.end_time,
                   ts.slot_kind, ts.source, ts.locked
            FROM timetable_slots ts
            JOIN course_offerings o ON o.id = ts.offering_id
            JOIN courses c ON c.id = o.course_id
            JOIN sections sec ON sec.id = ts.section_id
            JOIN users u ON u.id = ts.teacher_id
            JOIN rooms r ON r.id = ts.room_id
            """;

    static final RowMapper<SlotView> SLOT_MAPPER = (rs, i) -> new SlotView(
            rs.getLong("id"), rs.getLong("offering_id"), rs.getString("course_code"), rs.getString("course_title"),
            rs.getLong("section_id"), rs.getString("section_name"), rs.getLong("teacher_id"),
            rs.getString("teacher_name"), rs.getLong("room_id"), rs.getString("room_code"),
            rs.getString("room_type"), rs.getInt("day_of_week"),
            rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
            rs.getString("slot_kind"), rs.getString("source"), rs.getBoolean("locked"));

    private static final RowMapper<TermInfo> TERM_MAPPER = (rs, i) -> new TermInfo(
            rs.getLong("id"), rs.getString("name"), rs.getInt("working_days"), rs.getBoolean("is_current"));

    private final JdbcTemplate jdbc;

    public TimetableService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ reads

    /** termId == null means the current term. */
    public TermInfo resolveTerm(Long termId) {
        List<TermInfo> found = termId == null
                ? jdbc.query("SELECT id, name, working_days, is_current FROM academic_terms WHERE is_current", TERM_MAPPER)
                : jdbc.query("SELECT id, name, working_days, is_current FROM academic_terms WHERE id = ?", TERM_MAPPER, termId);
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    termId == null ? "No current academic term is set." : "Academic term not found.");
        }
        return found.get(0);
    }

    public List<TermInfo> terms() {
        return jdbc.query("SELECT id, name, working_days, is_current FROM academic_terms ORDER BY starts_on DESC", TERM_MAPPER);
    }

    public List<PeriodView> periods(long termId) {
        return jdbc.query("""
                SELECT period_no, start_time, end_time, break_after FROM period_slots
                WHERE term_id = ? ORDER BY period_no""",
                (rs, i) -> new PeriodView(rs.getInt("period_no"), rs.getObject("start_time", LocalTime.class),
                        rs.getObject("end_time", LocalTime.class), rs.getBoolean("break_after")),
                termId);
    }

    public enum Filter { SECTION, TEACHER, ROOM, LAB_ROOMS, ALL }

    public TimetableView view(TermInfo term, Filter filter, Long id) {
        String where = switch (filter) {
            case SECTION -> " AND ts.section_id = ?";
            case TEACHER -> " AND ts.teacher_id = ?";
            case ROOM -> " AND ts.room_id = ?";
            case LAB_ROOMS -> " AND r.room_type = 'LAB'";
            case ALL -> "";
        };
        String sql = SLOT_SELECT + " WHERE ts.term_id = ?" + where + " ORDER BY ts.day_of_week, ts.start_time, r.code";
        List<SlotView> slots = (filter == Filter.LAB_ROOMS || filter == Filter.ALL)
                ? jdbc.query(sql, SLOT_MAPPER, term.id())
                : jdbc.query(sql, SLOT_MAPPER, term.id(), id);
        return new TimetableView(term, periods(term.id()), slots);
    }

    public SlotView slot(long slotId) {
        List<SlotView> found = jdbc.query(SLOT_SELECT + " WHERE ts.id = ?", SLOT_MAPPER, slotId);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Timetable slot not found.");
        return found.get(0);
    }

    // ------------------------------------------------------------------ conflict detection

    private record OfferingInfo(long termId, long teacherId, long sectionId, int strength, int workingDays) {}

    private record RoomInfo(String code, int capacity, String type, boolean active) {}

    private OfferingInfo offering(long offeringId) {
        List<OfferingInfo> found = jdbc.query("""
                SELECT o.term_id, o.teacher_id, o.section_id, sec.strength, t.working_days
                FROM course_offerings o
                JOIN sections sec ON sec.id = o.section_id
                JOIN academic_terms t ON t.id = o.term_id
                WHERE o.id = ?""",
                (rs, i) -> new OfferingInfo(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getInt(5)),
                offeringId);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Course offering not found.");
        return found.get(0);
    }

    private RoomInfo room(long roomId, boolean lockForUpdate) {
        String sql = "SELECT code, capacity, room_type, active FROM rooms WHERE id = ?" + (lockForUpdate ? " FOR UPDATE" : "");
        List<RoomInfo> found = jdbc.query(sql,
                (rs, i) -> new RoomInfo(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getBoolean(4)), roomId);
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Room not found.");
        return found.get(0);
    }

    /** Dry run: what would placing this slot clash with? Changes nothing. */
    @Transactional(readOnly = true)
    public CheckResult check(SlotRequest req, Long excludeSlotId) {
        return check(req, excludeSlotId, offering(req.offeringId()), room(req.roomId(), false));
    }

    private CheckResult check(SlotRequest req, Long excludeSlotId, OfferingInfo o, RoomInfo room) {
        if (!req.endTime().isAfter(req.startTime())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "End time must be after start time.");
        }
        if (!room.active()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Room " + room.code() + " is not in use.");
        }

        // Hard conflicts: one query, using the same overlap operator as the exclusion constraints.
        List<Conflict> conflicts = new ArrayList<>();
        jdbc.query(SLOT_SELECT + """
                 WHERE ts.term_id = ? AND ts.day_of_week = ?
                   AND ts.period && timerange(CAST(? AS time), CAST(? AS time), '[)')
                   AND (ts.room_id = ? OR ts.teacher_id = ? OR ts.section_id = ?)
                   AND (CAST(? AS bigint) IS NULL OR ts.id <> CAST(? AS bigint))
                 ORDER BY ts.start_time""",
                rs -> {
                    SlotView s = SLOT_MAPPER.mapRow(rs, 0);
                    if (s.roomId() == req.roomId()) conflicts.add(new Conflict("ROOM", s));
                    if (s.teacherId() == o.teacherId()) conflicts.add(new Conflict("TEACHER", s));
                    if (s.sectionId() == o.sectionId()) conflicts.add(new Conflict("SECTION", s));
                },
                o.termId(), req.dayOfWeek(), req.startTime(), req.endTime(),
                req.roomId(), o.teacherId(), o.sectionId(), excludeSlotId, excludeSlotId);

        // Soft rules: allowed with explicit confirmation.
        List<Warning> warnings = new ArrayList<>();
        if (room.capacity() < o.strength()) {
            warnings.add(new Warning("CAPACITY", "Room " + room.code() + " holds " + room.capacity()
                    + " but the section has " + o.strength() + " students."));
        }
        boolean labRoom = "LAB".equals(room.type());
        if (req.kind() == SlotKind.LAB && !labRoom) {
            warnings.add(new Warning("ROOM_TYPE", "This is a lab session but " + room.code() + " is not a lab."));
        } else if (req.kind() == SlotKind.LECTURE && labRoom) {
            warnings.add(new Warning("ROOM_TYPE", "This is a lecture but " + room.code() + " is a lab."));
        }
        if (req.dayOfWeek() > o.workingDays()) {
            warnings.add(new Warning("NON_WORKING_DAY", "The term runs " + o.workingDays() + " days a week."));
        }
        Integer unavailable = jdbc.queryForObject("""
                SELECT count(*) FROM teacher_unavailability
                WHERE term_id = ? AND teacher_id = ? AND day_of_week = ?
                  AND period && timerange(CAST(? AS time), CAST(? AS time), '[)')""",
                Integer.class, o.termId(), o.teacherId(), req.dayOfWeek(), req.startTime(), req.endTime());
        if (unavailable != null && unavailable > 0) {
            warnings.add(new Warning("TEACHER_UNAVAILABLE", "The teacher marked this time as unavailable."));
        }
        List<PeriodView> grid = periods(o.termId());
        if (!grid.isEmpty() && (req.startTime().isBefore(grid.get(0).startTime())
                || req.endTime().isAfter(grid.get(grid.size() - 1).endTime()))) {
            warnings.add(new Warning("OUTSIDE_HOURS", "This is outside the term's teaching hours."));
        }
        return new CheckResult(conflicts, warnings);
    }

    private void rejectIfNotAllowed(CheckResult result, boolean force) {
        if (result.hasConflicts()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This time clashes with " + result.conflicts().size() + " existing class(es).")
                    .with("conflicts", result.conflicts()).with("warnings", result.warnings());
        }
        if (result.hasWarnings() && !force) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Check the warnings, then save again to confirm.")
                    .with("warnings", result.warnings());
        }
    }

    private void lockTermShared(long termId) {
        jdbc.queryForObject("SELECT id FROM academic_terms WHERE id = ? FOR SHARE", Long.class, termId);
    }

    // ------------------------------------------------------------------ manual edits

    @Transactional
    public SlotView create(SlotRequest req) {
        OfferingInfo o = offering(req.offeringId());
        lockTermShared(o.termId());
        RoomInfo room = room(req.roomId(), true);
        rejectIfNotAllowed(check(req, null, o, room), req.force());

        // If a concurrent request slips in between check and insert, the exclusion
        // constraints reject this insert and GlobalExceptionHandler returns 409.
        Long id = jdbc.queryForObject("""
                INSERT INTO timetable_slots (offering_id, term_id, teacher_id, section_id, room_id,
                                             day_of_week, start_time, end_time, slot_kind, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL') RETURNING id""",
                Long.class, req.offeringId(), o.termId(), o.teacherId(), o.sectionId(), req.roomId(),
                req.dayOfWeek(), req.startTime(), req.endTime(), req.kind().name());
        return slot(id);
    }

    /** Moving a slot makes it MANUAL, so regenerating the timetable keeps the admin's change. */
    @Transactional
    public SlotView move(long slotId, SlotRequest req) {
        slot(slotId);  // 404 if missing
        OfferingInfo o = offering(req.offeringId());
        lockTermShared(o.termId());
        RoomInfo room = room(req.roomId(), true);
        rejectIfNotAllowed(check(req, slotId, o, room), req.force());

        jdbc.update("""
                UPDATE timetable_slots
                SET offering_id = ?, term_id = ?, teacher_id = ?, section_id = ?, room_id = ?,
                    day_of_week = ?, start_time = ?, end_time = ?, slot_kind = ?, source = 'MANUAL'
                WHERE id = ?""",
                req.offeringId(), o.termId(), o.teacherId(), o.sectionId(), req.roomId(),
                req.dayOfWeek(), req.startTime(), req.endTime(), req.kind().name(), slotId);
        return slot(slotId);
    }

    @Transactional
    public void delete(long slotId) {
        if (jdbc.update("DELETE FROM timetable_slots WHERE id = ?", slotId) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Timetable slot not found.");
        }
    }

    /** A locked slot survives regeneration. */
    @Transactional
    public SlotView setLocked(long slotId, boolean locked) {
        if (jdbc.update("UPDATE timetable_slots SET locked = ? WHERE id = ?", locked, slotId) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Timetable slot not found.");
        }
        return slot(slotId);
    }

    // ------------------------------------------------------------------ setup data for the admin screen

    @Transactional(readOnly = true)
    public SetupView setup(Long termId) {
        TermInfo term = resolveTerm(termId);
        long t = term.id();
        return new SetupView(term, terms(), periods(t),
                jdbc.query("SELECT id, name, semester, strength FROM sections WHERE term_id = ? ORDER BY semester, name",
                        (rs, i) -> new SectionOption(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)), t),
                jdbc.query("SELECT id, code, building, capacity, room_type FROM rooms WHERE active ORDER BY room_type, code",
                        (rs, i) -> new RoomOption(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5))),
                jdbc.query("""
                        SELECT DISTINCT u.id, u.full_name FROM course_offerings o JOIN users u ON u.id = o.teacher_id
                        WHERE o.term_id = ? ORDER BY u.full_name""",
                        (rs, i) -> new TeacherOption(rs.getLong(1), rs.getString(2)), t),
                jdbc.query("""
                        SELECT o.id, c.code, c.title, sec.id, sec.name, u.id, u.full_name,
                               c.lecture_hours_per_week, c.lab_hours_per_week,
                               COALESCE(SUM(p.n) FILTER (WHERE ts.slot_kind = 'LECTURE'), 0) AS lec,
                               COALESCE(SUM(p.n) FILTER (WHERE ts.slot_kind = 'LAB'), 0) AS lab
                        FROM course_offerings o
                        JOIN courses c ON c.id = o.course_id
                        JOIN sections sec ON sec.id = o.section_id
                        JOIN users u ON u.id = o.teacher_id
                        LEFT JOIN timetable_slots ts ON ts.offering_id = o.id
                        LEFT JOIN LATERAL (
                            SELECT count(*) AS n FROM period_slots ps
                            WHERE ps.term_id = o.term_id AND ps.period && ts.period
                        ) p ON ts.id IS NOT NULL
                        WHERE o.term_id = ?
                        GROUP BY o.id, c.code, c.title, sec.id, sec.name, u.id, u.full_name,
                                 c.lecture_hours_per_week, c.lab_hours_per_week
                        ORDER BY sec.name, c.code""",
                        (rs, i) -> new OfferingOption(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                                rs.getString(5), rs.getLong(6), rs.getString(7), rs.getInt(8), rs.getInt(9),
                                rs.getInt(10), rs.getInt(11)), t));
    }
}
