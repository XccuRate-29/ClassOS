package com.classos.feedback;

import com.classos.Fixtures;
import com.classos.IntegrationTestBase;
import com.classos.feedback.FeedbackDtos.*;
import com.classos.security.AuthenticatedUser;
import com.classos.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackTest extends IntegrationTestBase {

    @Autowired FeedbackService feedback;
    @Autowired JdbcTemplate jdbc;

    @Test
    void susScoringFollowsTheStandardFormula() {
        assertEquals(100.0, FeedbackService.susScore(List.of(5, 1, 5, 1, 5, 1, 5, 1, 5, 1)));  // best possible
        assertEquals(0.0, FeedbackService.susScore(List.of(1, 5, 1, 5, 1, 5, 1, 5, 1, 5)));    // worst possible
        assertEquals(50.0, FeedbackService.susScore(List.of(3, 3, 3, 3, 3, 3, 3, 3, 3, 3)));   // all neutral
        assertThrows(IllegalArgumentException.class, () -> FeedbackService.susScore(List.of(3, 3)));
    }

    @Test
    void oneSusResponsePerUserIsStoredAndSummarised() {
        AuthenticatedUser u = new AuthenticatedUser(new Fixtures(jdbc).user("STUDENT"), "s@x", Role.STUDENT);
        assertFalse(feedback.mine(u).answered());
        feedback.submitSus(u, new SusRequest(List.of(3, 3, 3, 3, 3, 3, 3, 3, 3, 3), null));
        SusMine again = feedback.submitSus(u, new SusRequest(List.of(5, 1, 5, 1, 5, 1, 5, 1, 5, 1), "Clear"));
        assertEquals(100.0, again.score(), "a second response replaces the first");
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM sus_responses WHERE user_id = ?", Integer.class, u.id());
        assertEquals(1, rows);

        SusSummary s = feedback.susSummary();
        assertTrue(s.responses() >= 1);
        assertEquals(10, s.perItem().size());
    }

    @Test
    void csvCellsCannotBecomeSpreadsheetFormulas() {
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", FeedbackService.cell("=HYPERLINK(\"x\")"));
        assertEquals("\"'+1\"", FeedbackService.cell("+1"));
        assertEquals("\"plain, text\"", FeedbackService.cell("plain, text"));
        assertEquals("", FeedbackService.cell(null));
    }

    @Test
    void feedbackIsListedAndFilterable() {
        AuthenticatedUser u = new AuthenticatedUser(new Fixtures(jdbc).user("TEACHER"), "t@x", Role.TEACHER);
        feedback.submit(u, new FeedbackRequest("BUG", 2, "The QR code is too small on the projector", "/teacher/attendance"));
        List<FeedbackView> bugs = feedback.list("NEW", "BUG");
        assertTrue(bugs.stream().anyMatch(f -> f.message().startsWith("The QR code") && "TEACHER".equals(f.role())));
        long id = bugs.get(0).id();
        feedback.setStatus(id, "DONE");
        assertTrue(feedback.list("DONE", null).stream().anyMatch(f -> f.id() == id));
    }
}
