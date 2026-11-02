package com.classos.timetable;

import com.classos.security.AuthenticatedUser;
import com.classos.timetable.TimetableDtos.TermInfo;
import com.classos.timetable.TimetableDtos.TimetableView;
import com.classos.timetable.TimetableService.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Each role sees "their" timetable for the current term. */
@RestController
public class MyTimetableController {

    private final TimetableService timetable;
    private final JdbcTemplate jdbc;

    public MyTimetableController(TimetableService timetable, JdbcTemplate jdbc) {
        this.timetable = timetable;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/timetable/me")
    public TimetableView mine(@AuthenticationPrincipal AuthenticatedUser user) {
        TermInfo term = timetable.resolveTerm(null);
        return switch (user.role()) {
            case TEACHER -> timetable.view(term, Filter.TEACHER, user.id());
            case LAB_ASSISTANT -> timetable.view(term, Filter.LAB_ROOMS, null);
            case ADMIN -> timetable.view(term, Filter.ALL, null);
            case STUDENT -> {
                List<Long> section = jdbc.query(
                        "SELECT section_id FROM section_enrollments WHERE term_id = ? AND student_id = ?",
                        (rs, i) -> rs.getLong(1), term.id(), user.id());
                yield section.isEmpty()
                        ? new TimetableView(term, timetable.periods(term.id()), List.of())
                        : timetable.view(term, Filter.SECTION, section.get(0));
            }
        };
    }
}
