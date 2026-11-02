package com.classos.feedback;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;

public final class FeedbackDtos {
    private FeedbackDtos() {}

    public record FeedbackRequest(@NotNull @Pattern(regexp = "BUG|IDEA|PRAISE|OTHER") String category,
                                  @Min(1) @Max(5) Integer rating,
                                  @NotBlank @Size(max = 4000) String message,
                                  @Size(max = 200) String page) {}

    public record FeedbackView(long id, String userName, String role, String page, String category, Integer rating,
                               String message, String status, OffsetDateTime createdAt) {}

    public record StatusRequest(@NotNull @Pattern(regexp = "NEW|REVIEWED|DONE") String status) {}

    /** answers: the 10 SUS items in order, each 1 (strongly disagree) to 5 (strongly agree). */
    public record SusRequest(@NotNull @Size(min = 10, max = 10) List<@NotNull @Min(1) @Max(5) Integer> answers,
                             @Size(max = 4000) String comment) {}

    public record SusMine(boolean answered, Double score) {}

    public record RoleScore(String role, int responses, double averageScore) {}

    /** perItem: average answer (1-5) for each of the 10 statements, to see which ones pull the score down. */
    public record SusSummary(int responses, Double averageScore, List<RoleScore> byRole, List<Double> perItem) {}
}
