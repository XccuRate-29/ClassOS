package com.classos.attendance;

import com.classos.IntegrationTestBase;
import com.classos.attendance.AttendanceDtos.*;
import com.classos.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AttendanceIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired AttendanceSessionService sessions;
    @Autowired AttendanceMarkingService marking;

    private static final String IP = "127.0.0.1";

    private String mark(long student, long session, String code, String device) {
        return marking.mark(student, new MarkRequest(session, code, Method.QR), device, IP).outcome();
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    @Test
    void studentMarksOnceAndDuplicatesAreHarmless() {
        AttendanceFixture f = new AttendanceFixture(jdbc, 2);
        long s = sessions.start(f.teacher, new StartRequest(f.offering, null)).id();
        String code = sessions.currentCode(s);

        assertEquals("ACCEPTED", mark(f.students.get(0), s, code, f.devices.get(0)));
        assertEquals("ALREADY_MARKED", mark(f.students.get(0), s, code, f.devices.get(0)));
        assertEquals(1, sessions.detail(f.teacher, s).presentCount());
    }

    @Test
    void onePhoneCannotMarkTwoStudents() {
        AttendanceFixture f = new AttendanceFixture(jdbc, 2);
        long s = sessions.start(f.teacher, new StartRequest(f.offering, null)).id();
        String code = sessions.currentCode(s);
        String sharedPhone = f.devices.get(0);

        assertEquals("ACCEPTED", mark(f.students.get(0), s, code, sharedPhone));
        assertEquals("DEVICE_IN_USE", mark(f.students.get(1), s, code, sharedPhone));
    }

    @Test
    void studentCannotSwitchPhonesWithoutAReset() {
        AttendanceFixture f = new AttendanceFixture(jdbc, 1);
        long student = f.students.get(0);
        long s1 = sessions.start(f.teacher, new StartRequest(f.offering, null)).id();
        assertEquals("ACCEPTED", mark(student, s1, sessions.currentCode(s1), f.devices.get(0)));
        sessions.close(f.teacher, s1);

        long s2 = sessions.start(f.teacher, new StartRequest(f.offering, null)).id();
        String otherPhone = UUID.randomUUID().toString();
        assertEquals("DEVICE_MISMATCH", mark(student, s2, sessions.currentCode(s2), otherPhone));

        marking.resetDevice(student);
        assertEquals("ACCEPTED", mark(student, s2, sessions.currentCode(s2), otherPhone));
    }

    @Test
    void wrongCodesAreLimitedAndLogged() {
        AttendanceFixture f = new AttendanceFixture(jdbc, 1);
        long s = sessions.start(f.teacher, new StartRequest(f.offering, null)).id();
        String bad = wrong(sessions.currentCode(s));
        for (int i = 0; i < 5; i++) assertEquals("WRONG_CODE", mark(f.students.get(0), s, bad, f.devices.get(0)));
        // Even the right code is refused once the limit is reached.
        assertEquals("TOO_MANY_ATTEMPTS", mark(f.students.get(0), s, sessions.currentCode(s), f.devices.get(0)));
        assertEquals(5, sessions.detail(f.teacher, s).rejectedAttempts().get("WRONG_CODE"));
    }

    @Test
    void outsidersAndMissingDevicesAreRejected() {
        AttendanceFixture f = new AttendanceFixture(jdbc, 1);
        AttendanceFixture other = new AttendanceFixture(jdbc, 1);
        long s = sessions.start(f.teacher, new StartRequest(f.offering, null)).id();
        String code = sessions.currentCode(s);

        assertEquals("NOT_ENROLLED", mark(other.students.get(0), s, code, other.devices.get(0)));
        assertEquals("DEVICE_MISSING", mark(f.students.get(0), s, code, null));
        assertEquals("NOT_FOUND", mark(f.students.get(0), Long.MAX_VALUE, code, f.devices.get(0)));
    }

    @Test
    void closingMarksEveryoneElseAbsentAndStopsMarking() {
        AttendanceFixture f = new AttendanceFixture(jdbc, 3);
        long s = sessions.start(f.teacher, new StartRequest(f.offering, null)).id();
        String code = sessions.currentCode(s);
        mark(f.students.get(0), s, code, f.devices.get(0));

        SessionDetail closed = sessions.close(f.teacher, s);
        assertEquals("CLOSED", closed.status());
        assertEquals(1, closed.presentCount());
        assertEquals(2, closed.students().stream().filter(r -> "ABSENT".equals(r.status())).count());

        assertEquals("SESSION_CLOSED", mark(f.students.get(1), s, code, f.devices.get(1)));
    }

    @Test
    void teacherCorrectionsWinAndOnlyTheOwnerCanSeeASession() {
        AttendanceFixture f = new AttendanceFixture(jdbc, 1);
        long s = sessions.start(f.teacher, new StartRequest(f.offering, null)).id();
        sessions.close(f.teacher, s);
        SessionDetail fixed = sessions.manual(f.teacher, s, new ManualRequest(f.students.get(0), true));
        assertEquals("PRESENT", fixed.students().get(0).status());
        assertEquals("MANUAL", fixed.students().get(0).method());

        AttendanceFixture other = new AttendanceFixture(jdbc, 0);
        ApiException e = assertThrows(ApiException.class, () -> sessions.detail(other.teacher, s));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void onlyOneOpenSessionPerTeacher() {
        AttendanceFixture f = new AttendanceFixture(jdbc, 1);
        sessions.start(f.teacher, new StartRequest(f.offering, null));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> sessions.start(f.teacher, new StartRequest(f.offering, null)));
    }

    @Test
    void teacherCannotOpenAnotherTeachersCourse() {
        AttendanceFixture f = new AttendanceFixture(jdbc, 0);
        AttendanceFixture other = new AttendanceFixture(jdbc, 0);
        ApiException e = assertThrows(ApiException.class,
                () -> sessions.start(other.teacher, new StartRequest(f.offering, null)));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
    }
}
