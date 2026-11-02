package com.classos.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

// Errors are returned as RFC 7807 ProblemDetail JSON.
@RestControllerAdvice
public class GlobalExceptionHandler {

    // When two requests race, the loser is stopped by a database constraint rather than
    // by the Java pre-check. These messages explain what happened in user terms.
    private static final Map<String, String> CONSTRAINT_MESSAGES = Map.ofEntries(
            Map.entry("no_room_overlap", "Another change just took this room at this time. Refresh and try again."),
            Map.entry("no_teacher_overlap", "Another change just gave this teacher a class at this time. Refresh and try again."),
            Map.entry("no_section_overlap", "Another change just gave this section a class at this time. Refresh and try again."),
            Map.entry("no_booking_overlap", "Someone just booked this room for an overlapping time. Pick another time."),
            Map.entry("uq_users_email", "A user with this email already exists."),
            Map.entry("uq_one_open_session_per_offering", "Attendance is already open for this class."),
            Map.entry("uq_one_open_session_per_teacher", "You already have attendance open for another class. Close it first."),
            Map.entry("departments_code_key", "A department with this code already exists."),
            Map.entry("academic_terms_name_key", "A term with this name already exists."),
            Map.entry("rooms_code_key", "A room with this code already exists."),
            Map.entry("courses_code_key", "A course with this code already exists."),
            Map.entry("uq_section_name", "This term already has a section with this name in this department."),
            Map.entry("uq_offering", "This section already takes this course in this term."),
            Map.entry("fk_offering_section", "That section belongs to a different term."),
            Map.entry("no_period_overlap", "Two periods overlap."),
            Map.entry("no_unavail_overlap", "This overlaps another unavailable time for the same teacher."),
            Map.entry("uq_one_submission_per_student", "Your submission was just received from another tab. Refresh to see it."),
            Map.entry("chk_grade_after_due", "The grading deadline must be on or after the due date."));

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        ex.getProperties().forEach(pd::setProperty);
        return pd;
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ProblemDetail handleTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "That file is too large to upload.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        pd.setProperty("fields", fields);
        return pd;
    }

    // The raw database message is not returned, to avoid leaking schema details;
    // only the name of a known constraint is mapped to a friendly message.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        String raw = String.valueOf(ex.getMostSpecificCause().getMessage());
        for (Map.Entry<String, String> e : CONSTRAINT_MESSAGES.entrySet()) {
            if (raw.contains(e.getKey())) {
                ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getValue());
                pd.setProperty("constraint", e.getKey());
                return pd;
            }
        }
        // "violates check constraint" from enforce_user_role() has no constraint name; recognise its text.
        if (raw.contains("exceed the maximum")) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Marks can't be more than the assignment's maximum.");
        }
        if (raw.contains("must have role")) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "That person doesn't have the right role for this.");
        }
        // SQLSTATE 23503: deleting something that other rows still point to.
        if (ex.getMostSpecificCause() instanceof java.sql.SQLException sql && "23503".equals(sql.getSQLState())) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "This is still in use (for example by offerings, timetable slots or attendance records), so it can't be changed or removed.");
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with existing data");
    }
}
