package com.classos.grading;

import java.time.OffsetDateTime;
import java.util.List;

public final class GradingDtos {
    private GradingDtos() {}

    public enum Policy { FCFS, PRIORITY, ROUND_ROBIN }

    /** plannedStart / plannedFinish: when this submission would be graded if the teacher starts now. */
    public record QueueItem(int position, long submissionId, int version, long assignmentId, String assignmentTitle,
                            String courseCode, String sectionName, String studentName, OffsetDateTime submittedAt,
                            boolean late, int priority, int estMinutes, OffsetDateTime gradeBy,
                            OffsetDateTime plannedStart, OffsetDateTime plannedFinish) {}

    /** Times in hours. fairness: Jain's index over each assignment's average wait (1 = equal). */
    public record PolicyMetrics(String policy, int jobs, double avgWaitHours, double maxWaitHours,
                                double avgTurnaroundHours, int deadlineMisses, double fairness) {}

    public record QueueView(String policy, double agingPerHour, int quantum, boolean fallback, String note,
                            List<QueueItem> items, PolicyMetrics metrics) {}

    public record CompareView(int jobs, double agingPerHour, int quantum, List<PolicyMetrics> policies) {}
}
