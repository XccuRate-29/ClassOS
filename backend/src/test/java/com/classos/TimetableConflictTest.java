package com.classos;

import com.classos.common.ApiException;
import com.classos.timetable.TimetableDtos.*;
import com.classos.timetable.TimetableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimetableConflictTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired TimetableService timetable;

    long term, teacher1, teacher2, offering1, offering2, offeringSameTeacher, roomBig, roomSmall, lab;

    @BeforeEach
    void setUp() {
        Fixtures f = new Fixtures(jdbc);
        term = f.term(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 15), 5);
        f.period(term, 1, "09:00", "10:00", false);
        f.period(term, 2, "10:00", "11:00", false);
        long sectionA = f.section(term, "A", 60);
        long sectionB = f.section(term, "B", 60);
        teacher1 = f.user("TEACHER");
        teacher2 = f.user("TEACHER");
        long course = f.course("C", 3, 0);
        long course2 = f.course("K", 3, 0);
        offering1 = f.offering(term, course, sectionA, teacher1);
        offering2 = f.offering(term, course, sectionB, teacher2);
        offeringSameTeacher = f.offering(term, course2, sectionB, teacher1);
        roomBig = f.room("BIG", 70, "CLASSROOM");
        roomSmall = f.room("SML", 40, "CLASSROOM");
        lab = f.room("LAB", 70, "LAB");
    }

    private SlotRequest req(long offering, long room, int day, String start, String end, boolean force) {
        return new SlotRequest(offering, room, day, LocalTime.parse(start), LocalTime.parse(end), SlotKind.LECTURE, force);
    }

    private static List<String> types(ApiException e) {
        @SuppressWarnings("unchecked")
        List<Conflict> conflicts = (List<Conflict>) e.getProperties().get("conflicts");
        return conflicts.stream().map(Conflict::type).toList();
    }

    @Test
    void roomClashIsReportedWithTheClashingClass() {
        timetable.create(req(offering1, roomBig, 1, "09:00", "10:00", false));
        ApiException e = assertThrows(ApiException.class,
                () -> timetable.create(req(offering2, roomBig, 1, "09:30", "10:30", false)));
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
        assertEquals(List.of("ROOM"), types(e));
    }

    @Test
    void teacherClashInADifferentRoomIsReported() {
        timetable.create(req(offering1, roomBig, 2, "09:00", "10:00", false));
        ApiException e = assertThrows(ApiException.class,
                () -> timetable.create(req(offeringSameTeacher, roomSmall, 2, "09:00", "10:00", true)));
        assertTrue(types(e).contains("TEACHER"));
    }

    @Test
    void warningsNeedConfirmationButConflictsNeverPass() {
        // Section of 60 in a 40-seat room: a warning, not a conflict.
        ApiException warn = assertThrows(ApiException.class,
                () -> timetable.create(req(offering1, roomSmall, 3, "09:00", "10:00", false)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, warn.getStatus());
        SlotView saved = timetable.create(req(offering1, roomSmall, 3, "09:00", "10:00", true));
        assertEquals("MANUAL", saved.source());

        // force=true does not override a real clash.
        ApiException clash = assertThrows(ApiException.class,
                () -> timetable.create(req(offering2, roomSmall, 3, "09:00", "10:00", true)));
        assertEquals(HttpStatus.CONFLICT, clash.getStatus());
    }

    @Test
    void lectureInALabRoomIsAWarning() {
        CheckResult r = timetable.check(req(offering1, lab, 4, "09:00", "10:00", false), null);
        assertFalse(r.hasConflicts());
        assertTrue(r.warnings().stream().anyMatch(w -> w.type().equals("ROOM_TYPE")));
    }

    @Test
    void movingASlotDoesNotConflictWithItself() {
        SlotView s = timetable.create(req(offering1, roomBig, 5, "09:00", "10:00", false));
        SlotView moved = timetable.move(s.id(), req(offering1, roomBig, 5, "09:30", "10:30", true));
        assertEquals(LocalTime.of(9, 30), moved.startTime());
    }

    @Test
    void backToBackIsFine() {
        timetable.create(req(offering1, roomBig, 1, "10:00", "11:00", false));
        assertDoesNotThrow(() -> timetable.create(req(offering2, roomBig, 1, "11:00", "11:30", true)));
    }
}
