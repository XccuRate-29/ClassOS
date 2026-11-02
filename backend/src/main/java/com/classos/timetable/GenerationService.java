package com.classos.timetable;

import com.classos.common.ApiException;
import com.classos.config.ClassosProperties;
import com.classos.timetable.TimetableDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.*;

/**
 * Builds the solver input from the database, calls the C++ solver, and saves the result.
 *
 * Everything happens in one transaction: if the solver is down or returns something
 * invalid, the deleted slots come back and nothing is half-saved.
 *
 * Trade-off: the term and room locks are held while the solver runs (bounded by
 * classos.solver.time-limit-ms). Other timetable edits and room bookings wait for that
 * long. Acceptable for a rare admin action; a background job would avoid it.
 */
@Service
public class GenerationService {

    /** Lab hours are scheduled in blocks of this many consecutive periods. */
    static final int LAB_BLOCK_PERIODS = 2;

    private final JdbcTemplate jdbc;
    private final TimetableService timetable;
    private final SolverClient solver;
    private final int timeLimitMs;

    public GenerationService(JdbcTemplate jdbc, TimetableService timetable, SolverClient solver, ClassosProperties props) {
        this.jdbc = jdbc;
        this.timetable = timetable;
        this.solver = solver;
        this.timeLimitMs = props.solver().timeLimitMs();
    }

    private record OfferingRow(long id, long teacherId, long sectionId, int strength, int lectureHours,
                               int labHours, String courseCode, String sectionName) {}

    private record KeptSlot(long offeringId, long teacherId, long sectionId, long roomId, int day,
                            LocalTime start, LocalTime end, String kind) {}

    private record EventInfo(OfferingRow offering, String kind, int length) {}

    @Transactional
    public GenerationResult generate(Long termId, long requestedBy) {
        TermInfo term = timetable.resolveTerm(termId);
        long t = term.id();

        // Lock order: term, then rooms by id (same order everywhere, so no deadlock).
        jdbc.queryForObject("SELECT id FROM academic_terms WHERE id = ? FOR UPDATE", Long.class, t);
        jdbc.query("SELECT id FROM rooms ORDER BY id FOR UPDATE", rs -> {});

        List<PeriodView> periods = timetable.periods(t);
        if (periods.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Set up the period grid for " + term.name() + " first.");
        }
        int days = term.workingDays();

        int replaced = jdbc.update("DELETE FROM timetable_slots WHERE term_id = ? AND source = 'GENERATED' AND NOT locked", t);

        List<SolverProtocol.Room> rooms = jdbc.query(
                "SELECT id, capacity, room_type FROM rooms WHERE active ORDER BY id",
                (rs, i) -> new SolverProtocol.Room(rs.getLong(1), rs.getInt(2), rs.getString(3)));

        List<OfferingRow> offerings = jdbc.query("""
                SELECT o.id, o.teacher_id, o.section_id, sec.strength, c.lecture_hours_per_week,
                       c.lab_hours_per_week, c.code, sec.name
                FROM course_offerings o
                JOIN courses c ON c.id = o.course_id
                JOIN sections sec ON sec.id = o.section_id
                WHERE o.term_id = ? ORDER BY o.id""",
                (rs, i) -> new OfferingRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getInt(5),
                        rs.getInt(6), rs.getString(7), rs.getString(8)), t);

        List<KeptSlot> kept = jdbc.query("""
                SELECT offering_id, teacher_id, section_id, room_id, day_of_week, start_time, end_time, slot_kind
                FROM timetable_slots WHERE term_id = ?""",
                (rs, i) -> new KeptSlot(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getInt(5),
                        rs.getObject(6, LocalTime.class), rs.getObject(7, LocalTime.class), rs.getString(8)), t);

        // Kept slots become fixed occupancy, and count towards each offering's weekly hours.
        List<SolverProtocol.Fixed> fixed = new ArrayList<>();
        Map<Long, Integer> lecturePeriodsDone = new HashMap<>();
        Map<Long, Integer> labBlocksDone = new HashMap<>();
        for (KeptSlot k : kept) {
            List<Integer> covered = coveredPeriods(periods, k.start(), k.end());
            if ("LECTURE".equals(k.kind())) lecturePeriodsDone.merge(k.offeringId(), Math.max(1, covered.size()), Integer::sum);
            else labBlocksDone.merge(k.offeringId(), 1, Integer::sum);
            if (k.day() > days || covered.isEmpty()) continue;  // outside the grid: cannot collide with generated slots
            for (int[] run : contiguousRuns(covered)) {
                fixed.add(new SolverProtocol.Fixed(k.roomId(), k.teacherId(), k.sectionId(), k.day(), run[0], run[1]));
            }
        }

        // Turn weekly hours into events: 1-period lectures and 2-period lab blocks.
        List<SolverProtocol.Event> events = new ArrayList<>();
        Map<Integer, EventInfo> info = new HashMap<>();
        int nextId = 1;
        for (OfferingRow o : offerings) {
            int lectures = Math.max(0, o.lectureHours() - lecturePeriodsDone.getOrDefault(o.id(), 0));
            for (int i = 0; i < lectures; i++) {
                info.put(nextId, new EventInfo(o, "LECTURE", 1));
                events.add(new SolverProtocol.Event(nextId++, o.id(), o.teacherId(), o.sectionId(), o.strength(), "LECTURE", 1));
            }
            List<Integer> blocks = labBlocks(o.labHours());
            for (int b = labBlocksDone.getOrDefault(o.id(), 0); b < blocks.size(); b++) {
                int len = blocks.get(b);
                info.put(nextId, new EventInfo(o, "LAB", len));
                events.add(new SolverProtocol.Event(nextId++, o.id(), o.teacherId(), o.sectionId(), o.strength(), "LAB", len));
            }
        }

        List<SolverProtocol.Unavailable> unavailable = new ArrayList<>();
        jdbc.query("SELECT teacher_id, day_of_week, start_time, end_time FROM teacher_unavailability WHERE term_id = ?",
                rs -> {
                    int day = rs.getInt(2);
                    if (day > days) return;
                    for (int p : coveredPeriods(periods, rs.getObject(3, LocalTime.class), rs.getObject(4, LocalTime.class))) {
                        unavailable.add(new SolverProtocol.Unavailable(rs.getLong(1), day, p));
                    }
                }, t);

        List<Integer> breakAfter = periods.stream()
                .filter(p -> p.breakAfter() && p.periodNo() < periods.size())
                .map(PeriodView::periodNo).toList();

        SolverProtocol.Response res = events.isEmpty()
                ? new SolverProtocol.Response(true, List.of(), List.of(), List.of(), new SolverProtocol.Stats(0, 0, 0, false))
                : solver.solve(new SolverProtocol.Request(days, periods.size(), breakAfter, timeLimitMs,
                        rooms, events, fixed, unavailable));

        if (res.violations() != null && !res.violations().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "The solver returned an invalid timetable; nothing was saved.")
                    .with("violations", res.violations());
        }

        List<Object[]> rows = new ArrayList<>();
        for (SolverProtocol.Placement pl : res.placements()) {
            EventInfo e = info.get(pl.eventId());
            if (e == null) throw new ApiException(HttpStatus.BAD_GATEWAY, "The solver returned an unknown event.");
            LocalTime start = periods.get(pl.startPeriod() - 1).startTime();
            LocalTime end = periods.get(pl.startPeriod() + e.length() - 2).endTime();
            rows.add(new Object[]{e.offering().id(), t, e.offering().teacherId(), e.offering().sectionId(), pl.roomId(),
                    pl.day(), start, end, e.kind()});
        }
        // The exclusion constraints check every row again as it is inserted.
        jdbc.batchUpdate("""
                INSERT INTO timetable_slots (offering_id, term_id, teacher_id, section_id, room_id,
                                             day_of_week, start_time, end_time, slot_kind, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'GENERATED')""", rows);

        List<UnplacedEvent> unplaced = new ArrayList<>();
        for (SolverProtocol.Unplaced u : res.unplaced()) {
            EventInfo e = info.get(u.eventId());
            if (e == null) continue;
            unplaced.add(new UnplacedEvent(e.offering().id(), e.offering().courseCode(), e.offering().sectionName(),
                    e.kind(), e.length(), u.reason()));
        }

        String status = unplaced.isEmpty() ? "COMPLETE" : "PARTIAL";
        SolverProtocol.Stats st = res.stats();
        jdbc.update("""
                INSERT INTO timetable_runs (term_id, requested_by, status, events_total, events_placed,
                                            nodes_explored, backtracks, solver_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                t, requestedBy, status, events.size(), rows.size(), st.nodes(), st.backtracks(), st.elapsedMs());

        return new GenerationResult(term, status, events.size(), rows.size(), replaced, kept.size(),
                st.nodes(), st.backtracks(), st.elapsedMs(), st.limitReached(), unplaced);
    }

    public List<RunView> runs(long termId) {
        return jdbc.query("""
                SELECT r.id, r.status, r.events_total, r.events_placed, r.nodes_explored, r.backtracks,
                       r.solver_ms, u.full_name, r.created_at
                FROM timetable_runs r JOIN users u ON u.id = r.requested_by
                WHERE r.term_id = ? ORDER BY r.created_at DESC LIMIT 20""",
                (rs, i) -> new RunView(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getLong(5),
                        rs.getLong(6), rs.getLong(7), rs.getString(8),
                        rs.getObject(9, java.time.OffsetDateTime.class)), termId);
    }

    /** 1-based numbers of the grid periods that overlap [start, end). */
    static List<Integer> coveredPeriods(List<PeriodView> periods, LocalTime start, LocalTime end) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < periods.size(); i++) {
            PeriodView p = periods.get(i);
            if (p.startTime().isBefore(end) && start.isBefore(p.endTime())) out.add(i + 1);
        }
        return out;
    }

    /** [start, length] pairs for consecutive numbers, e.g. [2,3,5] -> [2,2], [5,1]. */
    static List<int[]> contiguousRuns(List<Integer> sorted) {
        List<int[]> runs = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1) == sorted.get(j) + 1) j++;
            runs.add(new int[]{sorted.get(i), j - i + 1});
            i = j + 1;
        }
        return runs;
    }

    /** 5 lab hours -> blocks [2, 2, 1]. */
    static List<Integer> labBlocks(int labHours) {
        List<Integer> blocks = new ArrayList<>();
        for (int left = labHours; left > 0; left -= LAB_BLOCK_PERIODS) blocks.add(Math.min(LAB_BLOCK_PERIODS, left));
        return blocks;
    }
}
