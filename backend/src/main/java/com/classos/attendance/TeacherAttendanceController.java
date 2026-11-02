package com.classos.attendance;

import com.classos.attendance.AttendanceDtos.*;
import com.classos.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// /api/teacher/** is limited to TEACHER and ADMIN (SecurityConfig); each session is
// further limited to the teacher who opened it.
@RestController
@RequestMapping("/api/teacher/attendance")
public class TeacherAttendanceController {

    private final AttendanceSessionService sessions;

    public TeacherAttendanceController(AttendanceSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/today")
    public TeacherToday today(@AuthenticationPrincipal AuthenticatedUser user) {
        return sessions.today(user.id());
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDetail start(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody StartRequest req) {
        return sessions.start(user.id(), req);
    }

    @GetMapping("/sessions/{id}/code")
    public CodeView code(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return sessions.code(user.id(), id);
    }

    @GetMapping("/sessions/{id}")
    public SessionDetail detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return sessions.detail(user.id(), id);
    }

    @PostMapping("/sessions/{id}/manual")
    public SessionDetail manual(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                                @Valid @RequestBody ManualRequest req) {
        return sessions.manual(user.id(), id, req);
    }

    @PostMapping("/sessions/{id}/close")
    public SessionDetail close(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        return sessions.close(user.id(), id);
    }

    @GetMapping("/history")
    public List<SessionHistoryRow> history(@AuthenticationPrincipal AuthenticatedUser user) {
        return sessions.history(user.id());
    }
}
