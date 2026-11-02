package com.classos.timetable;

import java.util.List;

/** JSON contract with the C++ solver service. See docs/solver-protocol.md. */
public final class SolverProtocol {
    private SolverProtocol() {}

    public record Request(int days, int periodsPerDay, List<Integer> breakAfter, int timeLimitMs,
                          List<Room> rooms, List<Event> events, List<Fixed> fixed,
                          List<Unavailable> teacherUnavailable) {}

    public record Room(long id, int capacity, String type) {}

    public record Event(int id, long offeringId, long teacherId, long sectionId, int size, String kind, int length) {}

    /** 0 means "does not occupy this kind of resource". */
    public record Fixed(long roomId, long teacherId, long sectionId, int day, int startPeriod, int length) {}

    public record Unavailable(long teacherId, int day, int period) {}

    public record Response(boolean complete, List<Placement> placements, List<Unplaced> unplaced,
                           List<String> violations, Stats stats) {}

    public record Placement(int eventId, int day, int startPeriod, long roomId) {}

    public record Unplaced(int eventId, String reason) {}

    public record Stats(long nodes, long backtracks, long elapsedMs, boolean limitReached) {}

    // ---- grading queue scheduler (POST /v1/schedule); all times in minutes relative to "now"

    public record ScheduleRequest(List<String> policies, ScheduleParams params, List<ScheduleJob> jobs) {}

    public record ScheduleParams(double agingPerHour, double priorityStep, int quantum, double start) {}

    public record ScheduleJob(long id, long queue, double arrival, double burst, int priority, double deadline) {}

    public record ScheduleResponse(List<ScheduleResult> results) {}

    public record ScheduleResult(String policy, List<ScheduleSlot> order, ScheduleMetrics metrics) {}

    public record ScheduleSlot(long jobId, double start, double finish) {}

    public record ScheduleMetrics(int jobs, double avgWait, double maxWait, double avgTurnaround,
                                  int deadlineMisses, double fairness) {}
}
