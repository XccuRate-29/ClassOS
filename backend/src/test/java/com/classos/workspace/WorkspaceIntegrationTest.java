package com.classos.workspace;

import com.classos.Fixtures;
import com.classos.IntegrationTestBase;
import com.classos.common.ApiException;
import com.classos.security.AuthenticatedUser;
import com.classos.user.Role;
import com.classos.workspace.WorkspaceDtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceIntegrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired WorkspaceService workspace;

    long offering;
    AuthenticatedUser teacher, otherTeacher, student, outsider;

    @BeforeEach
    void setUp() {
        Fixtures f = new Fixtures(jdbc);
        long term = f.term(LocalDate.now().minusDays(30), LocalDate.now().plusDays(90), 5);
        long section = f.section(term, "A", 30), otherSection = f.section(term, "B", 30);
        teacher = new AuthenticatedUser(f.user("TEACHER"), "t@x", Role.TEACHER);
        otherTeacher = new AuthenticatedUser(f.user("TEACHER"), "o@x", Role.TEACHER);
        student = new AuthenticatedUser(f.user("STUDENT"), "s@x", Role.STUDENT);
        outsider = new AuthenticatedUser(f.user("STUDENT"), "x@x", Role.STUDENT);
        offering = f.offering(term, f.course("C", 3, 0), section, teacher.id());
        jdbc.update("INSERT INTO section_enrollments (section_id, term_id, student_id) VALUES (?, ?, ?)", section, term, student.id());
        jdbc.update("INSERT INTO section_enrollments (section_id, term_id, student_id) VALUES (?, ?, ?)", otherSection, term, outsider.id());
    }

    private static MockMultipartFile file(String name, String text) {
        return new MockMultipartFile("file", name, "application/octet-stream", text.getBytes());
    }

    private AssignmentView assignment(OffsetDateTime due, boolean acceptLate) {
        return workspace.createAssignment(offering, teacher,
                new AssignmentRequest("Lab 1", "Implement FCFS", due, null, new BigDecimal("10"), acceptLate, 2, 10), null);
    }

    @Test
    void materialsAreVisibleOnlyInsideTheCourse() throws Exception {
        MaterialView m = workspace.addMaterial(offering, teacher, "Week 1 notes", null, file("week1.pdf", "scheduling"));

        ResponseEntity<Resource> download = workspace.downloadMaterial(m.id(), student);
        assertEquals("scheduling", new String(download.getBody().getInputStream().readAllBytes()));
        assertTrue(download.getHeaders().getFirst("Content-Disposition").startsWith("attachment"));
        assertEquals("nosniff", download.getHeaders().getFirst("X-Content-Type-Options"));

        for (AuthenticatedUser stranger : new AuthenticatedUser[]{outsider, otherTeacher}) {
            ApiException e = assertThrows(ApiException.class, () -> workspace.downloadMaterial(m.id(), stranger));
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }
        ApiException notTeacher = assertThrows(ApiException.class,
                () -> workspace.addMaterial(offering, student, "x", null, file("x.pdf", "x")));
        assertEquals(HttpStatus.FORBIDDEN, notTeacher.getStatus());
    }

    @Test
    void resubmissionInvalidatesAGradeInProgress() {
        AssignmentView a = assignment(OffsetDateTime.now().plusDays(3), true);
        MySubmission v1 = workspace.submit(a.id(), student, file("answer.c", "v1"));
        assertEquals(1, v1.version());

        MySubmission v2 = workspace.submit(a.id(), student, file("answer.c", "v2"));  // while the teacher grades v1
        assertEquals(2, v2.version());
        ApiException stale = assertThrows(ApiException.class,
                () -> workspace.grade(v1.id(), teacher, new GradeRequest(new BigDecimal("8"), "Good", 1)));
        assertEquals(HttpStatus.CONFLICT, stale.getStatus());
        assertEquals(2, stale.getProperties().get("currentVersion"));

        SubmissionRow graded = workspace.grade(v2.id(), teacher, new GradeRequest(new BigDecimal("8.5"), "Good", 2));
        assertEquals("GRADED", graded.status());
        ApiException locked = assertThrows(ApiException.class, () -> workspace.submit(a.id(), student, file("answer.c", "v3")));
        assertEquals(HttpStatus.CONFLICT, locked.getStatus());

        MySubmission seen = workspace.workspace(offering, student).assignments().get(0).mine();
        assertEquals(0, new BigDecimal("8.5").compareTo(seen.marks()));
        assertEquals("Good", seen.feedback());
    }

    @Test
    void lateWorkFollowsTheAssignmentsRule() {
        AssignmentView closed = assignment(OffsetDateTime.now().minusHours(1), false);
        ApiException e = assertThrows(ApiException.class, () -> workspace.submit(closed.id(), student, file("a.txt", "x")));
        assertEquals(HttpStatus.CONFLICT, e.getStatus());

        AssignmentView lenient = assignment(OffsetDateTime.now().minusHours(1), true);
        assertTrue(workspace.submit(lenient.id(), student, file("a.txt", "x")).late());
    }

    @Test
    void marksAreCappedAndOthersCannotSubmitOrSee() {
        AssignmentView a = assignment(OffsetDateTime.now().plusDays(1), true);
        MySubmission s = workspace.submit(a.id(), student, file("a.txt", "x"));
        ApiException tooMany = assertThrows(ApiException.class,
                () -> workspace.grade(s.id(), teacher, new GradeRequest(new BigDecimal("11"), null, 1)));
        assertEquals(HttpStatus.BAD_REQUEST, tooMany.getStatus());

        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> workspace.submit(a.id(), outsider, file("a.txt", "x"))).getStatus());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> workspace.downloadSubmission(s.id(), outsider)).getStatus());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> workspace.grade(s.id(), student,
                        new GradeRequest(BigDecimal.TEN, null, 1))).getStatus());
    }

    @Test
    void assignmentsWithWorkCannotBeDeleted() {
        AssignmentView a = assignment(OffsetDateTime.now().plusDays(1), true);
        workspace.submit(a.id(), student, file("a.txt", "x"));
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ApiException.class, () -> workspace.deleteAssignment(a.id(), teacher)).getStatus());

        AssignmentView empty = assignment(OffsetDateTime.now().plusDays(1), true);
        assertDoesNotThrow(() -> workspace.deleteAssignment(empty.id(), teacher));
    }

    @Test
    void teacherSeesWhoHasNotSubmitted() {
        AssignmentView a = assignment(OffsetDateTime.now().plusDays(1), true);
        AssignmentDetail before = workspace.detail(a.id(), teacher);
        assertEquals(1, before.notSubmitted().size());
        workspace.submit(a.id(), student, file("a.txt", "x"));
        AssignmentDetail after = workspace.detail(a.id(), teacher);
        assertEquals(0, after.notSubmitted().size());
        assertEquals(1, after.submissions().size());
        assertTrue(workspace.detail(a.id(), student).submissions().isEmpty(), "students never see other submissions");
    }
}
