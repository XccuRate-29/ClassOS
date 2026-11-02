package com.classos.setup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Requests and views for the admin setup screens. Validation mirrors the database CHECKs. */
public final class SetupDtos {
    private SetupDtos() {}

    // ---- departments
    public record DepartmentRequest(@NotBlank @Size(max = 16) String code, @NotBlank @Size(max = 120) String name) {}
    public record DepartmentView(long id, String code, String name) {}

    // ---- terms and the period grid
    public record TermRequest(@NotBlank @Size(max = 40) String name,
                              @NotNull LocalDate startsOn,
                              @NotNull LocalDate endsOn,
                              @NotNull @Min(1) @Max(7) Integer workingDays) {}

    public record PeriodRequest(@NotNull LocalTime startTime, @NotNull LocalTime endTime, boolean breakAfter) {}

    public record PeriodGridRequest(@NotEmpty @Size(max = 24) List<@Valid PeriodRequest> periods) {}

    public record PeriodView(int periodNo, LocalTime startTime, LocalTime endTime, boolean breakAfter) {}

    public record TermView(long id, String name, LocalDate startsOn, LocalDate endsOn, int workingDays,
                           boolean current, List<PeriodView> periods) {}

    // ---- rooms
    public record RoomRequest(@NotBlank @Size(max = 20) String code,
                              @NotBlank @Size(max = 60) String building,
                              @NotNull @Min(1) @Max(2000) Integer capacity,
                              @NotNull @Pattern(regexp = "CLASSROOM|LAB|SEMINAR_HALL") String roomType,
                              Boolean active) {}

    public record RoomView(long id, String code, String building, int capacity, String roomType, boolean active) {}

    // ---- courses
    public record CourseRequest(@NotNull Long departmentId,
                                @NotBlank @Size(max = 20) String code,
                                @NotBlank @Size(max = 150) String title,
                                @NotNull @Min(1) @Max(10) Integer credits,
                                @NotNull @Min(0) @Max(40) Integer lectureHoursPerWeek,
                                @NotNull @Min(0) @Max(40) Integer labHoursPerWeek) {}

    public record CourseView(long id, long departmentId, String departmentCode, String code, String title,
                             int credits, int lectureHoursPerWeek, int labHoursPerWeek) {}

    // ---- sections and enrolment
    public record SectionRequest(@NotNull Long termId, @NotNull Long departmentId,
                                 @NotBlank @Size(max = 20) String name,
                                 @NotNull @Min(1) @Max(10) Integer semester,
                                 @NotNull @Min(1) @Max(1000) Integer strength) {}

    public record SectionView(long id, long termId, long departmentId, String departmentCode, String name,
                              int semester, int strength, int enrolled) {}

    public record StudentView(long id, String fullName, String email) {}

    public record EnrollRequest(@NotEmpty @Size(max = 500) List<@NotBlank String> emails) {}

    /** Every email lands in exactly one list, so the admin can see what happened to each. */
    public record EnrollResult(int added, List<String> alreadyInSection, List<String> inOtherSection,
                               List<String> notFound, List<String> notStudents) {}

    // ---- offerings
    public record OfferingRequest(@NotNull Long termId, @NotNull Long courseId, @NotNull Long sectionId,
                                  @NotNull Long teacherId) {}

    public record TeacherChangeRequest(@NotNull Long teacherId) {}

    public record OfferingView(long id, long courseId, String courseCode, String courseTitle, long sectionId,
                               String sectionName, long teacherId, String teacherName) {}

    public record TeacherView(long id, String fullName, String email) {}

    // ---- teacher unavailability
    public record UnavailabilityRequest(@NotNull Long termId, @NotNull Long teacherId,
                                        @NotNull @Min(1) @Max(7) Integer dayOfWeek,
                                        @NotNull LocalTime startTime, @NotNull LocalTime endTime,
                                        @Size(max = 200) String reason) {}

    public record UnavailabilityView(long id, long teacherId, String teacherName, int dayOfWeek,
                                     LocalTime startTime, LocalTime endTime, String reason) {}
}
