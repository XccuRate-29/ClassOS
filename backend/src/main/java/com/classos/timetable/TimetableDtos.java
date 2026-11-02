package com.classos.timetable;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;
import java.util.List;

public final class TimetableDtos {
    private TimetableDtos() {}

    public enum SlotKind { LECTURE, LAB }

    public record TermInfo(long id, String name, int workingDays, boolean current) {}

    public record PeriodView(int periodNo, LocalTime startTime, LocalTime endTime, boolean breakAfter) {}

    public record SlotView(long id, long offeringId, String courseCode, String courseTitle,
                           long sectionId, String sectionName, long teacherId, String teacherName,
                           long roomId, String roomCode, String roomType,
                           int dayOfWeek, LocalTime startTime, LocalTime endTime,
                           String kind, String source, boolean locked) {}

    public record TimetableView(TermInfo term, List<PeriodView> periods, List<SlotView> slots) {}

    /** force = true saves despite warnings (never despite conflicts). */
    public record SlotRequest(@NotNull Long offeringId,
                              @NotNull Long roomId,
                              @NotNull @Min(1) @Max(7) Integer dayOfWeek,
                              @NotNull LocalTime startTime,
                              @NotNull LocalTime endTime,
                              @NotNull SlotKind kind,
                              boolean force) {}

    /** type: ROOM, TEACHER or SECTION, the resource that would be double-booked. */
    public record Conflict(String type, SlotView slot) {}

    /** Soft-rule problems that an admin may knowingly accept. */
    public record Warning(String type, String message) {}

    public record CheckResult(List<Conflict> conflicts, List<Warning> warnings) {
        public boolean hasConflicts() { return !conflicts.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }

    public record LockRequest(boolean locked) {}

    public record GenerateRequest(Long termId) {}

    public record UnplacedEvent(long offeringId, String courseCode, String sectionName, String kind,
                                int lengthPeriods, String reason) {}

    public record GenerationResult(TermInfo term, String status, int eventsTotal, int eventsPlaced,
                                   int slotsReplaced, int slotsKept, long nodesExplored, long backtracks,
                                   long solverMs, boolean limitReached, List<UnplacedEvent> unplaced) {}

    public record RunView(long id, String status, int eventsTotal, int eventsPlaced, long nodesExplored,
                          long backtracks, long solverMs, String requestedBy, java.time.OffsetDateTime createdAt) {}

    // Reference data for the admin timetable screen.
    public record SectionOption(long id, String name, int semester, int strength) {}
    public record RoomOption(long id, String code, String building, int capacity, String roomType) {}
    public record TeacherOption(long id, String fullName) {}
    public record OfferingOption(long id, String courseCode, String courseTitle, long sectionId, String sectionName,
                                 long teacherId, String teacherName, int lectureHoursPerWeek, int labHoursPerWeek,
                                 int scheduledLecturePeriods, int scheduledLabPeriods) {}

    public record SetupView(TermInfo term, List<TermInfo> terms, List<PeriodView> periods,
                            List<SectionOption> sections, List<RoomOption> rooms, List<TeacherOption> teachers,
                            List<OfferingOption> offerings) {}
}
