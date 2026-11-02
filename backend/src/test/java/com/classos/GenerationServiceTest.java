package com.classos;

import com.classos.common.ApiException;
import com.classos.timetable.GenerationService;
import com.classos.timetable.SolverClient;
import com.classos.timetable.SolverProtocol;
import com.classos.timetable.TimetableDtos.*;
import com.classos.timetable.TimetableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Tests the Java side of generation with a fake solver: building the request,
 * mapping periods back to clock times, keeping manual slots, and rollback on failure.
 * The real algorithm is tested in core/tests/test_timetable.cpp.
 */
class GenerationServiceTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired GenerationService generation;
    @Autowired TimetableService timetable;
    @MockBean SolverClient solver;

    long term, offering, classroom, lab, admin;
    final AtomicReference<SolverProtocol.Request> lastRequest = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        Fixtures f = new Fixtures(jdbc);
        // Fresh "current" term so resolveTerm(null) is not involved: tests pass the id.
        term = f.term(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 15), 5);
        f.period(term, 1, "09:00", "10:00", false);
        f.period(term, 2, "10:00", "11:00", false);
        f.period(term, 3, "11:00", "12:00", false);
        long section = f.section(term, "A", 50);
        long teacher = f.user("TEACHER");
        offering = f.offering(term, f.course("C", 2, 2), section, teacher);  // 2 lectures + one 2-period lab
        classroom = f.room("CR", 60, "CLASSROOM");
        lab = f.room("LB", 60, "LAB");
        admin = f.user("ADMIN");

        // Manual slot covering one of the two lectures.
        timetable.create(new SlotRequest(offering, classroom, 1, LocalTime.of(9, 0), LocalTime.of(10, 0), SlotKind.LECTURE, false));

        // Fake solver: lecture on Tuesday P1 in the classroom, lab on Wednesday P2-P3 in the lab.
        when(solver.solve(any())).thenAnswer(inv -> {
            SolverProtocol.Request r = inv.getArgument(0);
            lastRequest.set(r);
            List<SolverProtocol.Placement> placements = new ArrayList<>();
            for (SolverProtocol.Event e : r.events()) {
                placements.add("LAB".equals(e.kind())
                        ? new SolverProtocol.Placement(e.id(), 3, 2, lab)
                        : new SolverProtocol.Placement(e.id(), 2, 1, classroom));
            }
            return new SolverProtocol.Response(true, placements, List.of(), List.of(),
                    new SolverProtocol.Stats(3, 0, 1, false));
        });
    }

    private int slotCount() {
        return jdbc.queryForObject("SELECT count(*) FROM timetable_slots WHERE term_id = ?", Integer.class, term);
    }

    @Test
    void buildsRequestFromRemainingHoursAndSavesClockTimes() {
        GenerationResult result = generation.generate(term, admin);

        SolverProtocol.Request sent = lastRequest.get();
        assertEquals(2, sent.events().size(), "one lecture still needed + one lab block");
        assertEquals(1, sent.fixed().size(), "the manual slot is passed as fixed");
        assertEquals("COMPLETE", result.status());
        assertEquals(3, slotCount());

        LocalTime[] labTimes = jdbc.queryForObject("""
                SELECT start_time, end_time FROM timetable_slots
                WHERE term_id = ? AND slot_kind = 'LAB'""",
                (rs, i) -> new LocalTime[]{rs.getObject(1, LocalTime.class), rs.getObject(2, LocalTime.class)}, term);
        assertEquals(LocalTime.of(10, 0), labTimes[0]);
        assertEquals(LocalTime.of(12, 0), labTimes[1]);
    }

    @Test
    void regeneratingReplacesOnlyGeneratedSlots() {
        generation.generate(term, admin);
        GenerationResult second = generation.generate(term, admin);
        assertEquals(2, second.slotsReplaced());
        assertEquals(3, slotCount());
        Integer manual = jdbc.queryForObject(
                "SELECT count(*) FROM timetable_slots WHERE term_id = ? AND source = 'MANUAL'", Integer.class, term);
        assertEquals(1, manual);
    }

    @Test
    void solverFailureRollsBackEverything() {
        generation.generate(term, admin);
        // doThrow, not when(...): when() would call the existing fake with a null argument.
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "down")).when(solver).solve(any());
        assertThrows(ApiException.class, () -> generation.generate(term, admin));
        assertEquals(3, slotCount(), "the delete of generated slots was rolled back");
    }
}
