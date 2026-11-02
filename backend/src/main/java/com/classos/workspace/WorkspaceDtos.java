package com.classos.workspace;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class WorkspaceDtos {
    private WorkspaceDtos() {}

    /** toGrade is filled for teachers, pending (open assignments not yet submitted) for students. */
    public record CourseCard(long offeringId, String courseCode, String courseTitle, String sectionName,
                             String teacherName, int materials, int assignments, int toGrade, int pending) {}

    public record MaterialView(long id, String title, String description, String fileName, long sizeBytes,
                               OffsetDateTime createdAt) {}

    /** gradeBy defaults to a week after dueAt; priority 1 = high, 3 = low; estMinutes = grading time per submission. */
    public record AssignmentRequest(@NotBlank @Size(max = 200) String title,
                                    @Size(max = 20000) String instructions,
                                    @NotNull OffsetDateTime dueAt,
                                    OffsetDateTime gradeBy,
                                    @NotNull @DecimalMin("0.5") @DecimalMax("1000") BigDecimal maxMarks,
                                    Boolean acceptLate,
                                    @Min(1) @Max(3) Integer priority,
                                    @Min(1) @Max(240) Integer estMinutes) {}

    public record MySubmission(long id, String fileName, OffsetDateTime submittedAt, boolean late, int version,
                               String status, BigDecimal marks, String feedback) {}

    public record AssignmentView(long id, long offeringId, String title, String instructions, String attachmentName,
                                 OffsetDateTime dueAt, OffsetDateTime gradeBy, BigDecimal maxMarks, boolean acceptLate,
                                 int priority, int estMinutes, int submitted, int graded, MySubmission mine) {}

    public record CourseWorkspace(CourseCard course, boolean canTeach, List<MaterialView> materials,
                                  List<AssignmentView> assignments) {}

    public record SubmissionRow(long id, long studentId, String studentName, String studentEmail, String fileName,
                                OffsetDateTime submittedAt, boolean late, int version, String status,
                                BigDecimal marks, String feedback) {}

    public record StudentRef(long id, String fullName, String email) {}

    public record AssignmentDetail(AssignmentView assignment, CourseCard course, boolean canTeach,
                                   List<SubmissionRow> submissions, List<StudentRef> notSubmitted) {}

    /** version = the submission version the teacher downloaded; stale versions are refused. */
    public record GradeRequest(@NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal marks,
                               @Size(max = 5000) String feedback,
                               @NotNull @Min(1) Integer version) {}
}
