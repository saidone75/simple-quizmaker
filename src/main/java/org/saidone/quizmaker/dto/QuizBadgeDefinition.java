package org.saidone.quizmaker.dto;

import jakarta.validation.constraints.*;

import java.util.HashSet;
import java.util.List;

/** Stable IDs keep an earned badge recognizable when a teacher edits its presentation. */
public record QuizBadgeDefinition(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String id,
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Size(max = 16) String emoji,
        @NotNull @Min(0) @Max(100) Integer minimumPercentage) {

    public static List<QuizBadgeDefinition> defaults() {
        return List.of(new QuizBadgeDefinition("QUIZ_STAR", "Stella del quiz", "⭐", 80),
                new QuizBadgeDefinition("QUIZ_PERFECT", "Senza errori", "🏆", 100));
    }

    public static List<QuizBadgeDefinition> normalize(List<QuizBadgeDefinition> definitions) {
        if (definitions == null) return defaults();
        if (definitions.size() > 8) throw new IllegalArgumentException("Puoi impostare al massimo 8 badge per quiz");
        var ids = new HashSet<String>();
        return definitions.stream().map(badge -> {
            if (badge == null || badge.id() == null || !badge.id().matches("[A-Za-z0-9_-]{1,64}")
                    || badge.name() == null || badge.name().isBlank() || badge.name().length() > 80
                    || badge.emoji() == null || badge.emoji().isBlank() || badge.emoji().length() > 16
                    || badge.minimumPercentage() == null || badge.minimumPercentage() < 0 || badge.minimumPercentage() > 100) {
                throw new IllegalArgumentException("Ogni badge richiede un nome, un’emoji e una soglia tra 0 e 100");
            }
            if (!ids.add(badge.id())) throw new IllegalArgumentException("Gli identificativi dei badge devono essere univoci");
            return new QuizBadgeDefinition(badge.id(), badge.name().trim(), badge.emoji().trim(), badge.minimumPercentage());
        }).toList();
    }

    public String description() {
        if (minimumPercentage == 0) return "Hai completato questo quiz e affrontato una nuova sfida!";
        return minimumPercentage == 100 ? "Tutte le risposte corrette in questo quiz."
                : "Almeno il " + minimumPercentage + "% di risposte corrette in questo quiz.";
    }
}
