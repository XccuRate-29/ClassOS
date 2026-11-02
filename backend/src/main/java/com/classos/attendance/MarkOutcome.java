package com.classos.attendance;

import org.springframework.http.HttpStatus;

/** Every possible result of a marking attempt, with the status and message the student sees. */
public enum MarkOutcome {
    ACCEPTED(HttpStatus.OK, "Marked present."),
    ALREADY_MARKED(HttpStatus.OK, "Your attendance for this class is already recorded."),
    WRONG_CODE(HttpStatus.UNPROCESSABLE_ENTITY, "That code is not right. Check the number on the screen and try again."),
    EXPIRED_CODE(HttpStatus.UNPROCESSABLE_ENTITY, "That code has expired. Scan or type the code on the screen now."),
    SESSION_CLOSED(HttpStatus.CONFLICT, "Attendance for this class is closed."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "This attendance session doesn't exist."),
    NOT_ENROLLED(HttpStatus.FORBIDDEN, "You're not in the section this class is for."),
    DEVICE_MISMATCH(HttpStatus.FORBIDDEN,
            "Your account is registered to a different phone. Ask your teacher to mark you, or the admin to reset your device."),
    DEVICE_IN_USE(HttpStatus.FORBIDDEN, "This phone is already registered to another student."),
    DEVICE_MISSING(HttpStatus.BAD_REQUEST, "Your browser didn't send a device id. Reload the page and try again."),
    NETWORK_BLOCKED(HttpStatus.FORBIDDEN, "Connect to the campus Wi-Fi to mark attendance."),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong codes for this class. Ask your teacher to mark you.");

    public final HttpStatus status;
    public final String message;

    MarkOutcome(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /** Guesses that count towards the per-session attempt limit. */
    public boolean isFailedGuess() {
        return this == WRONG_CODE || this == EXPIRED_CODE;
    }
}
