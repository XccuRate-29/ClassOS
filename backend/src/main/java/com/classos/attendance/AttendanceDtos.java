package com.classos.attendance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class AttendanceDtos {
    private AttendanceDtos() {}

    // ---- teacher
    public record StartRequest(@NotNull Long offeringId, Long slotId) {}

    public record TodayClass(Long slotId, long offeringId, String courseCode, String courseTitle, String sectionName,
                             String roomCode, LocalTime startTime, LocalTime endTime, Long openSessionId) {}

    public record OfferingOption(long offeringId, String courseCode, String courseTitle, String sectionName) {}

    public record TeacherToday(LocalDate date, List<TodayClass> classes, List<OfferingOption> offerings,
                               Long openSessionId) {}

    /** code is null once the session is closed. validForMs = time until the next rotation. */
    public record CodeView(long sessionId, String code, int windowSeconds, long validForMs, Instant serverTime,
                           String status, int presentCount, int enrolledCount, Instant autoCloseAt) {}

    public record StudentRow(long studentId, String fullName, String email, String status, String method,
                             OffsetDateTime markedAt) {}

    public record SessionDetail(long id, String courseCode, String courseTitle, String sectionName, String roomCode,
                                LocalDate sessionDate, String status, OffsetDateTime openedAt,
                                OffsetDateTime autoCloseAt, OffsetDateTime closedAt, int enrolledCount,
                                int presentCount, List<StudentRow> students, Map<String, Integer> rejectedAttempts) {}

    public record ManualRequest(@NotNull Long studentId, boolean present) {}

    public record SessionHistoryRow(long id, LocalDate sessionDate, String courseCode, String sectionName,
                                    String status, int present, int enrolled) {}

    // ---- student
    public enum Method { QR, OTP }

    public record MarkRequest(@NotNull Long sessionId, @NotBlank String code, @NotNull Method method) {}

    /** "detail" (not "message") so the frontend reads it like any other error body. */
    public record MarkResponse(String outcome, String detail, Long sessionId) {}

    public record OpenSession(long sessionId, String courseCode, String courseTitle, String teacherName,
                              String roomCode, OffsetDateTime autoCloseAt, boolean alreadyMarked) {}

    /** percent is null before any class has been held. */
    public record CourseAttendance(long offeringId, String courseCode, String courseTitle, int held, int present,
                                   Double percent, boolean belowMinimum) {}

    public record StudentSummary(int minimumPercent, List<CourseAttendance> courses) {}

    public record DeviceStatus(boolean registered, boolean thisDevice) {}
}
