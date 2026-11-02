package com.classos.feedback;

import com.classos.feedback.FeedbackDtos.*;
import com.classos.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class FeedbackController {

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    // ---- any signed-in user
    @PostMapping("/api/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public void submit(@AuthenticationPrincipal AuthenticatedUser u, @Valid @RequestBody FeedbackRequest r) {
        feedback.submit(u, r);
    }

    @GetMapping("/api/feedback/sus")
    public SusMine mySus(@AuthenticationPrincipal AuthenticatedUser u) {
        return feedback.mine(u);
    }

    @PostMapping("/api/feedback/sus")
    public SusMine submitSus(@AuthenticationPrincipal AuthenticatedUser u, @Valid @RequestBody SusRequest r) {
        return feedback.submitSus(u, r);
    }

    // ---- admin (/api/admin/** is ADMIN-only)
    @GetMapping("/api/admin/feedback")
    public List<FeedbackView> list(@RequestParam(required = false) String status,
                                   @RequestParam(required = false) String category) {
        return feedback.list(status, category);
    }

    @PatchMapping("/api/admin/feedback/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setStatus(@PathVariable long id, @Valid @RequestBody StatusRequest r) {
        feedback.setStatus(id, r.status());
    }

    @GetMapping("/api/admin/feedback/sus")
    public SusSummary susSummary() {
        return feedback.susSummary();
    }

    @GetMapping("/api/admin/feedback/export")
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("classos-feedback.csv").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(feedback.exportCsv());
    }
}
