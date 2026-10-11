package org.saidone.quizmaker.dto;

import jakarta.validation.constraints.*;

import java.util.List;

public record QuizBadgeGenerationRequest(
        @NotBlank @Size(max = 200) String title,
        @NotNull @Size(max = 30) List<@NotBlank @Size(max = 500) String> questionTexts) { }
