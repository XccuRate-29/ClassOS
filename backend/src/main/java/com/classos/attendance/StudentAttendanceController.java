package com.classos.attendance;

import com.classos.attendance.AttendanceDtos.*;
import com.classos.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// /api/student/** is STUDENT-only (SecurityConfig).
@RestController
@RequestMapping("/api/student/attendance")
public class StudentAttendanceController {

    private final AttendanceMarkingService marking;

    public StudentAttendanceController(AttendanceMarkingService marking) {
        this.marking = marking;
    }

    /**
     * The client IP comes from the connection. Behind a reverse proxy, configure
     * server.forward-headers-strategy so this is the student's address, not the proxy's,
     * and never trust X-Forwarded-For from untrusted clients.
     */
    @PostMapping("/mark")
    public ResponseEntity<MarkResponse> mark(@AuthenticationPrincipal AuthenticatedUser user,
                                             @Valid @RequestBody MarkRequest req,
                                             @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                                             HttpServletRequest http) {
        MarkResponse res = marking.mark(user.id(), req, deviceId, http.getRemoteAddr());
        return ResponseEntity.status(MarkOutcome.valueOf(res.outcome()).status).body(res);
    }

    @GetMapping("/open")
    public List<OpenSession> open(@AuthenticationPrincipal AuthenticatedUser user) {
        return marking.openSessions(user.id());
    }

    @GetMapping("/summary")
    public StudentSummary summary(@AuthenticationPrincipal AuthenticatedUser user) {
        return marking.summary(user.id());
    }

    @GetMapping("/device")
    public DeviceStatus device(@AuthenticationPrincipal AuthenticatedUser user,
                               @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return marking.deviceStatus(user.id(), deviceId);
    }
}
