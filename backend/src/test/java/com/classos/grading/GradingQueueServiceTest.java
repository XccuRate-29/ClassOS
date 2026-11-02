package com.classos.grading;

import com.classos.Fixtures;
import com.classos.IntegrationTestBase;
import com.classos.common.ApiException;
import com.classos.grading.GradingDtos.*;
import com.classos.timetable.SolverClient;
import com.classos.timetable.SolverProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * The Java side of the evaluation queue with a fake scheduler: building jobs from
 * submissions, mapping the returned order back, and the FCFS fallback. The algorithms
 * themselves are tested in core/tests/test_scheduler.cpp.
 */
class GradingQueueServiceTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired GradingQueueService queue;
    @MockBean SolverClient solver;

    long teacher, urgent, routine;
    final List<Long> submissions = new ArrayList<>();
    final AtomicReference<SolverProtocol.ScheduleRequest> sent = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        Fixtures f = new Fixtures(jdbc);
        long term = f.term(LocalDate.now().minusDays(30), LocalDate.now().plusDays(90), 5);
        long section = f.section(term, "A", 30);
        teacher = f.user("TEACHER");
        long offering = f.offering(term, f.course("C", 3, 0), section, teacher);
        urgent = assignment(offering, 1, 15);
        routine = assignment(offering, 3, 5);
        // Three submissions: routine (oldest), urgent, routine (newest).
        submissions.add(submit(routine, f.user("STUDENT"), 180));
        submissions.add(submit(urgent, f.user("STUDENT"), 120));
        submissions.add(submit(routine, f.user("STUDENT"), 60));

        // Fake scheduler: returns the jobs in reverse order, 10 minutes each.
        when(solver.schedule(any())).thenAnswer(inv -> {
            SolverProtocol.ScheduleRequest r = inv.getArgument(0);
            sent.set(r);
            List<SolverProtocol.ScheduleResult> results = new ArrayList<>();
            for (String p : r.policies()) {
                List<SolverProtocol.ScheduleSlot> order = new ArrayList<>();
                for (int i = r.jobs().size() - 1, k = 0; i >= 0; i--, k++) {
                    order.add(new SolverProtocol.ScheduleSlot(r.jobs().get(i).id(), k * 10, k * 10 + 10));
                }
                results.add(new SolverProtocol.ScheduleResult(p, order,
                        new SolverProtocol.ScheduleMetrics(order.size(), 120, 180, 130, 0, 0.9)));
            }
            return new SolverProtocol.ScheduleResponse(results);
        });
    }

    private long assignment(long offering, int priority, int estMinutes) {
        return jdbc.queryForObject("""
                INSERT INTO assignments (offering_id, title, due_at, grade_by, max_marks, priority, est_minutes, created_by)
                VALUES (?, 'A', now() - interval '1 day', now() + interval '2 days', 10, ?, ?, ?) RETURNING id""",
                Long.class, offering, priority, estMinutes, teacher);
    }

    private long submit(long assignment, long student, int minutesAgo) {
        UUID file = UUID.randomUUID();
        jdbc.update("INSERT INTO stored_files (id, original_name, content_type, size_bytes, sha256, uploaded_by) VALUES (?, 'a.txt', 'text/plain', 1, ?, ?)",
                file, "0".repeat(64), student);
        return jdbc.queryForObject("""
                INSERT INTO submissions (assignment_id, student_id, file_id, late, submitted_at)
                VALUES (?, ?, ?, false, now() - make_interval(mins => ?)) RETURNING id""",
                Long.class, assignment, student, file, minutesAgo);
    }

    @Test
    void jobsCarryArrivalBurstQueuePriorityAndDeadline() {
        QueueView v = queue.queue(teacher, Policy.PRIORITY, 0.5, 2);
        SolverProtocol.ScheduleRequest r = sent.get();
        assertEquals(List.of("PRIORITY"), r.policies());
        assertEquals(0.5, r.params().agingPerHour());
        assertEquals(2, r.params().quantum());
        assertEquals(3, r.jobs().size());

        SolverProtocol.ScheduleJob first = r.jobs().get(0);  // the oldest submission
        assertEquals(submissions.get(0), first.id());
        assertEquals(routine, first.queue());
        assertEquals(5, first.burst());
        assertEquals(3, first.priority());
        assertTrue(first.arrival() < -170 && first.arrival() > -190, "about 180 minutes ago: " + first.arrival());
        assertTrue(first.deadline() > 2 * 24 * 60 - 10, "grade-by is two days ahead");

        // The order the scheduler returned is the order shown, with planned times.
        assertEquals(submissions.get(2), v.items().get(0).submissionId());
        assertEquals(1, v.items().get(0).position());
        assertFalse(v.fallback());
        assertEquals(2.0, v.metrics().avgWaitHours());
    }

    @Test
    void schedulerOutageFallsBackToFirstComeFirstServed() {
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "down")).when(solver).schedule(any());
        QueueView v = queue.queue(teacher, Policy.ROUND_ROBIN, null, null);
        assertTrue(v.fallback());
        assertEquals("FCFS", v.policy());
        assertEquals(submissions, v.items().stream().map(QueueItem::submissionId).toList());
        assertNull(v.metrics());
    }

    @Test
    void compareRunsAllThreePolicies() {
        CompareView c = queue.compare(teacher, null, null);
        assertEquals(List.of("FCFS", "PRIORITY", "ROUND_ROBIN"), sent.get().policies());
        assertEquals(3, c.policies().size());
        assertEquals(3, c.jobs());
    }

    @Test
    void gradedWorkAndOtherTeachersWorkAreNotInTheQueue() {
        jdbc.update("UPDATE submissions SET status = 'GRADED', marks = 5, graded_by = ?, graded_at = now() WHERE id = ?",
                teacher, submissions.get(1));
        assertEquals(2, queue.queue(teacher, Policy.FCFS, null, null).items().size());
        long stranger = new Fixtures(jdbc).user("TEACHER");
        assertTrue(queue.queue(stranger, Policy.FCFS, null, null).items().isEmpty());
    }

    @Test
    void parametersAreValidated() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> queue.queue(teacher, Policy.ROUND_ROBIN, null, 0)).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> queue.queue(teacher, Policy.PRIORITY, -1.0, null)).getStatus());
    }
}
