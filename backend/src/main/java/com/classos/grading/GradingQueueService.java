package com.classos.grading;

import com.classos.common.ApiException;
import com.classos.config.ClassosProperties;
import com.classos.grading.GradingDtos.*;
import com.classos.timetable.SolverClient;
import com.classos.timetable.SolverProtocol;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The assignment evaluation queue: every ungraded submission in the teacher's courses,
 * ordered by a CPU-scheduling policy computed in the C++ core.
 *
 * Mapping (see core/include/classos/scheduler.hpp): job = submission, arrival = when it was
 * submitted, burst = the assignment's estimated grading minutes, queue = the assignment,
 * priority = the assignment's priority, deadline = its grade-by date. Times are sent as
 * minutes relative to now.
 *
 * Resilience: if the C++ service is down, the queue still works in FCFS order (computed
 * here), flagged as a fallback, so teachers are never blocked from grading.
 */
@Service
public class GradingQueueService {

    private final JdbcTemplate jdbc;
    private final SolverClient solver;
    private final Clock clock;
    private final ClassosProperties.Grading defaults;

    public GradingQueueService(JdbcTemplate jdbc, SolverClient solver, Clock campusClock, ClassosProperties props) {
        this.jdbc = jdbc;
        this.solver = solver;
        this.clock = campusClock;
        this.defaults = props.grading();
    }

    record Waiting(long submissionId, int version, long assignmentId, String assignmentTitle, String courseCode,
                   String sectionName, String studentName, OffsetDateTime submittedAt, boolean late, int priority,
                   int estMinutes, OffsetDateTime gradeBy) {}

    List<Waiting> waiting(long teacherId) {
        return jdbc.query("""
                SELECT s.id, s.version, a.id, a.title, c.code, sec.name, u.full_name, s.submitted_at, s.late,
                       a.priority, a.est_minutes, a.grade_by
                FROM submissions s
                JOIN assignments a ON a.id = s.assignment_id
                JOIN course_offerings o ON o.id = a.offering_id
                JOIN courses c ON c.id = o.course_id
                JOIN sections sec ON sec.id = o.section_id
                JOIN users u ON u.id = s.student_id
                WHERE o.teacher_id = ? AND s.status = 'SUBMITTED'
                ORDER BY s.submitted_at, s.id""",
                (rs, i) -> new Waiting(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class), rs.getBoolean(9),
                        rs.getInt(10), rs.getInt(11), rs.getObject(12, OffsetDateTime.class)),
                teacherId);
    }

    private static double minutesFrom(Instant now, OffsetDateTime t) {
        return Duration.between(now, t.toInstant()).toMillis() / 60_000.0;
    }

    private SolverProtocol.ScheduleResponse callScheduler(List<Waiting> waiting, List<String> policies,
                                                          double aging, int quantum, Instant now) {
        List<SolverProtocol.ScheduleJob> jobs = waiting.stream()
                .map(w -> new SolverProtocol.ScheduleJob(w.submissionId(), w.assignmentId(),
                        minutesFrom(now, w.submittedAt()), w.estMinutes(), w.priority(), minutesFrom(now, w.gradeBy())))
                .toList();
        return solver.schedule(new SolverProtocol.ScheduleRequest(policies,
                new SolverProtocol.ScheduleParams(aging, defaults.priorityStep(), quantum, 0), jobs));
    }

    private static PolicyMetrics hours(String policy, SolverProtocol.ScheduleMetrics m) {
        return new PolicyMetrics(policy, m.jobs(), round(m.avgWait() / 60), round(m.maxWait() / 60),
                round(m.avgTurnaround() / 60), m.deadlineMisses(), round(m.fairness()));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private void checkParams(double aging, int quantum) {
        if (aging < 0 || aging > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "Aging must be between 0 and 100 points per hour.");
        if (quantum < 1 || quantum > 100) throw new ApiException(HttpStatus.BAD_REQUEST, "Quantum must be between 1 and 100.");
    }

    public QueueView queue(long teacherId, Policy policy, Double agingPerHour, Integer quantum) {
        double aging = agingPerHour != null ? agingPerHour : defaults.agingPerHour();
        int q = quantum != null ? quantum : defaults.quantum();
        checkParams(aging, q);
        Instant now = clock.instant();
        List<Waiting> waiting = waiting(teacherId);
        Map<Long, Waiting> byId = waiting.stream().collect(Collectors.toMap(Waiting::submissionId, Function.identity()));
        if (waiting.isEmpty()) {
            return new QueueView(policy.name(), aging, q, false, null, List.of(), null);
        }

        try {
            SolverProtocol.ScheduleResult r = callScheduler(waiting, List.of(policy.name()), aging, q, now).results().get(0);
            List<QueueItem> items = new ArrayList<>();
            for (SolverProtocol.ScheduleSlot slot : r.order()) {
                Waiting w = byId.get(slot.jobId());
                if (w == null) continue;
                items.add(item(items.size() + 1, w, now.plusMillis(Math.round(slot.start() * 60_000)),
                        now.plusMillis(Math.round(slot.finish() * 60_000))));
            }
            return new QueueView(policy.name(), aging, q, false, null, items, hours(policy.name(), r.metrics()));
        } catch (ApiException e) {
            if (e.getStatus() != HttpStatus.SERVICE_UNAVAILABLE && e.getStatus() != HttpStatus.BAD_GATEWAY) throw e;
            // Fallback: FCFS computed here, so grading never stops because the C++ service is down.
            List<QueueItem> items = new ArrayList<>();
            Instant t = now;
            for (Waiting w : waiting.stream().sorted(Comparator.comparing(Waiting::submittedAt)
                    .thenComparing(Waiting::submissionId)).toList()) {
                Instant finish = t.plus(Duration.ofMinutes(w.estMinutes()));
                items.add(item(items.size() + 1, w, t, finish));
                t = finish;
            }
            return new QueueView("FCFS", aging, q, true,
                    "The scheduling service is unavailable, so this is first-come-first-served order.", items, null);
        }
    }

    /** All three policies on the same waiting submissions: the evidence for choosing one. */
    public CompareView compare(long teacherId, Double agingPerHour, Integer quantum) {
        double aging = agingPerHour != null ? agingPerHour : defaults.agingPerHour();
        int q = quantum != null ? quantum : defaults.quantum();
        checkParams(aging, q);
        List<Waiting> waiting = waiting(teacherId);
        if (waiting.isEmpty()) return new CompareView(0, aging, q, List.of());
        List<String> all = List.of(Policy.FCFS.name(), Policy.PRIORITY.name(), Policy.ROUND_ROBIN.name());
        List<PolicyMetrics> metrics = callScheduler(waiting, all, aging, q, clock.instant()).results().stream()
                .map(r -> hours(r.policy(), r.metrics())).toList();
        return new CompareView(waiting.size(), aging, q, metrics);
    }

    private static QueueItem item(int position, Waiting w, Instant start, Instant finish) {
        return new QueueItem(position, w.submissionId(), w.version(), w.assignmentId(), w.assignmentTitle(),
                w.courseCode(), w.sectionName(), w.studentName(), w.submittedAt(), w.late(), w.priority(),
                w.estMinutes(), w.gradeBy(), start.atOffset(ZoneOffset.UTC), finish.atOffset(ZoneOffset.UTC));
    }
}
