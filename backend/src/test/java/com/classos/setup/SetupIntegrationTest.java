package com.classos.setup;

import com.classos.Fixtures;
import com.classos.IntegrationTestBase;
import com.classos.common.ApiException;
import com.classos.setup.SetupDtos.*;
import com.classos.timetable.TimetableDtos.SlotKind;
import com.classos.timetable.TimetableDtos.SlotRequest;
import com.classos.timetable.TimetableService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SetupIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired SetupService setup;
    @Autowired TimetableService timetable;

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 6);
    }

    @Test
    void periodGridIsSortedNumberedAndValidated() {
        TermView t = setup.createTerm(new TermRequest(unique("T"), LocalDate.of(2027, 1, 5), LocalDate.of(2027, 5, 30), 6));
        // Given out of order, with a break flag on the last period (which is meaningless).
        TermView saved = setup.replacePeriods(t.id(), new PeriodGridRequest(List.of(
                new PeriodRequest(LocalTime.of(10, 0), LocalTime.of(11, 0), true),
                new PeriodRequest(LocalTime.of(9, 0), LocalTime.of(10, 0), false))));
        assertEquals(2, saved.periods().size());
        assertEquals(LocalTime.of(9, 0), saved.periods().get(0).startTime());
        assertEquals(2, saved.periods().get(1).periodNo());
        assertFalse(saved.periods().get(1).breakAfter());

        ApiException overlap = assertThrows(ApiException.class, () -> setup.replacePeriods(t.id(), new PeriodGridRequest(List.of(
                new PeriodRequest(LocalTime.of(9, 0), LocalTime.of(10, 0), false),
                new PeriodRequest(LocalTime.of(9, 30), LocalTime.of(10, 30), false)))));
        assertEquals(HttpStatus.BAD_REQUEST, overlap.getStatus());
        assertEquals(2, setup.terms().stream().filter(x -> x.id() == t.id()).findFirst().orElseThrow().periods().size(),
                "a rejected grid leaves the old one untouched");
    }

    @Test
    void onlyOneTermIsCurrent() {
        // Remember and restore the real current term so other tests are unaffected.
        List<Long> before = jdbc.query("SELECT id FROM academic_terms WHERE is_current", (rs, i) -> rs.getLong(1));
        TermView a = setup.createTerm(new TermRequest(unique("A"), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 1), 5));
        TermView b = setup.createTerm(new TermRequest(unique("B"), LocalDate.of(2027, 7, 1), LocalDate.of(2027, 12, 1), 5));
        setup.makeCurrent(a.id());
        setup.makeCurrent(b.id());
        Integer current = jdbc.queryForObject("SELECT count(*) FROM academic_terms WHERE is_current", Integer.class);
        assertEquals(1, current);
        assertTrue(setup.terms().stream().anyMatch(x -> x.id() == b.id() && x.current()));
        if (!before.isEmpty()) setup.makeCurrent(before.get(0));
    }

    @Test
    void enrolmentReportsWhatHappenedToEachEmail() {
        Fixtures f = new Fixtures(jdbc);
        long term = f.term(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 1), 5);
        SectionView a = setup.createSection(new SectionRequest(term, f.dept, "a" + f.s, 3, 60));
        SectionView b = setup.createSection(new SectionRequest(term, f.dept, "b" + f.s, 3, 60));
        assertEquals(("A" + f.s).toUpperCase(), a.name(), "names are stored in upper case");

        String s1 = email(f.user("STUDENT")), s2 = email(f.user("STUDENT")), t1 = email(f.user("TEACHER"));
        setup.enroll(b.id(), new EnrollRequest(List.of(s2)));

        EnrollResult r = setup.enroll(a.id(), new EnrollRequest(List.of(
                s1.toUpperCase(), s1, s2, t1, "nobody@test.local", "  ")));
        assertEquals(1, r.added());                       // s1 once, even though listed twice in different case
        assertEquals(List.of(s2), r.inOtherSection());
        assertEquals(List.of(t1), r.notStudents());
        assertEquals(List.of("nobody@test.local"), r.notFound());

        assertEquals(1, setup.enroll(a.id(), new EnrollRequest(List.of(s1))).alreadyInSection().size());
        ApiException e = assertThrows(ApiException.class, () -> setup.deleteSection(a.id()));
        assertEquals(HttpStatus.CONFLICT, e.getStatus());
    }

    private String email(long userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    @Test
    void changingATeacherIntoAClashIsRejected() {
        Fixtures f = new Fixtures(jdbc);
        long term = f.term(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 1), 5);
        long secA = f.section(term, "A", 30), secB = f.section(term, "B", 30);
        long busy = f.user("TEACHER"), other = f.user("TEACHER");
        long room1 = f.room("R1", 40, "CLASSROOM"), room2 = f.room("R2", 40, "CLASSROOM");
        OfferingView o1 = setup.createOffering(new OfferingRequest(term, f.course("C", 3, 0), secA, busy));
        OfferingView o2 = setup.createOffering(new OfferingRequest(term, f.course("K", 3, 0), secB, other));
        timetable.create(new SlotRequest(o1.id(), room1, 1, LocalTime.of(9, 0), LocalTime.of(10, 0), SlotKind.LECTURE, true));
        timetable.create(new SlotRequest(o2.id(), room2, 1, LocalTime.of(9, 0), LocalTime.of(10, 0), SlotKind.LECTURE, true));

        assertThrows(DataIntegrityViolationException.class,
                () -> setup.changeTeacher(o2.id(), new TeacherChangeRequest(busy)));
        assertEquals(other, setup.offerings(term).stream().filter(o -> o.id() == o2.id()).findFirst().orElseThrow().teacherId());
    }

    @Test
    void onlyTeachersCanBeAssignedAndDuplicatesAreRefused() {
        Fixtures f = new Fixtures(jdbc);
        long term = f.term(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 1), 5);
        long sec = f.section(term, "A", 30);
        long course = f.course("C", 3, 0);
        long student = f.user("STUDENT"), teacher = f.user("TEACHER");
        assertThrows(DataIntegrityViolationException.class,
                () -> setup.createOffering(new OfferingRequest(term, course, sec, student)));
        setup.createOffering(new OfferingRequest(term, course, sec, teacher));
        assertThrows(DataIntegrityViolationException.class,
                () -> setup.createOffering(new OfferingRequest(term, course, sec, teacher)));
    }

    @Test
    void coursesNeedHoursAndRoomsAreDeactivatedNotDeleted() {
        Fixtures f = new Fixtures(jdbc);
        ApiException e = assertThrows(ApiException.class,
                () -> setup.createCourse(new CourseRequest(f.dept, unique("Z"), "Nothing", 2, 0, 0)));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());

        RoomView r = setup.createRoom(new RoomRequest(unique("r"), "Block B", 50, "LAB", null));
        assertTrue(r.active());
        assertEquals(r.code(), r.code().toUpperCase());
        RoomView off = setup.updateRoom(r.id(), new RoomRequest(r.code(), "Block B", 50, "LAB", false));
        assertFalse(off.active());
    }
}
