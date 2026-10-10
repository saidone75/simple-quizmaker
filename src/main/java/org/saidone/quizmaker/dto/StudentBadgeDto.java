package org.saidone.quizmaker.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class StudentBadgeDto {
    private StudentBadgeDto() { }

    public record EarnedBadge(String key, String code, String name, String emoji,
                              String description, String category, UUID quizId,
                              String quizTitle, LocalDateTime earnedAt) { }

    public record Milestone(String code, String name, String emoji, String description,
                            int progress, int target, boolean earned) { }

    public record Collection(UUID studentId, String studentName, List<EarnedBadge> badges,
                             List<Milestone> milestones, int completedQuizzes,
                             int activeDays, int bestStreak) { }
}
