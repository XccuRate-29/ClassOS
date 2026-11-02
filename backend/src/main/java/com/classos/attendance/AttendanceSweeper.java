package com.classos.attendance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background thread that closes sessions past their time limit, so absentees are
 * recorded even if the teacher never presses Close. Runs on Spring's scheduler thread.
 */
@Component
public class AttendanceSweeper {

    private static final Logger log = LoggerFactory.getLogger(AttendanceSweeper.class);

    private final AttendanceSessionService sessions;

    public AttendanceSweeper(AttendanceSessionService sessions) {
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${classos.attendance.sweep-ms:30000}", initialDelay = 10000)
    public void closeExpiredSessions() {
        try {
            int closed = sessions.closeExpired();
            if (closed > 0) log.info("Closed {} expired attendance session(s)", closed);
        } catch (RuntimeException e) {
            log.warn("Attendance sweep failed; will retry", e);  // never let the scheduler thread die
        }
    }
}
