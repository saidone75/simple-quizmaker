package org.saidone.quizmaker.service;

import lombok.val;

import org.saidone.quizmaker.dto.StudentBadgeDto;
import org.saidone.quizmaker.dto.QuizBadgeDefinition;
import org.saidone.quizmaker.entity.Student;
import org.saidone.quizmaker.entity.StudentQuizAttempt;

import java.time.LocalDate;
import java.util.*;

/** Awards are derived from immutable attempts, so an earned badge is never revoked. */
public final class StudentBadgeEvaluator {
    private StudentBadgeEvaluator() { }

    private enum Badge {
        FIRST_QUIZ("Primo passo", "🌱", "Completa il tuo primo quiz.", "Percorso", 1),
        FIVE_QUIZZES("Esploratore", "🧭", "Completa 5 quiz diversi.", "Percorso", 5),
        TEN_QUIZZES("Grande esploratore", "🚀", "Completa 10 quiz diversi.", "Percorso", 10),
        THREE_DAYS("Presenza costante", "📅", "Completa quiz in 3 giorni diversi.", "Costanza", 3),
        SEVEN_DAYS("Con impegno", "💪", "Completa quiz in 7 giorni diversi.", "Costanza", 7);

        final String name;
        final String emoji;
        final String description;
        final String category;
        final int target;

        Badge(String name, String emoji, String description, String category, int target) {
            this.name = name;
            this.emoji = emoji;
            this.description = description;
            this.category = category;
            this.target = target;
        }
    }

    public static StudentBadgeDto.Collection evaluate(Student student, List<StudentQuizAttempt> history) {
        val attempts = history.stream()
                .filter(a -> a.getTotalQuestions() > 0 && a.getScore() >= 0
                        && a.getScore() <= a.getTotalQuestions() && a.getCompletedAt() != null)
                .sorted(Comparator.comparing(StudentQuizAttempt::getCompletedAt)
                        .thenComparing(a -> a.getId().toString()))
                .toList();
        val earned = new LinkedHashMap<String, StudentBadgeDto.EarnedBadge>();
        val quizzes = new HashSet<UUID>();
        val days = new HashSet<LocalDate>();
        LocalDate previousDay = null;
        int streak = 0;
        int bestStreak = 0;

        for (val attempt : attempts) {
            quizzes.add(attempt.getQuizId());
            val day = attempt.getCompletedAt().toLocalDate();
            if (days.add(day)) {
                streak = previousDay != null && day.equals(previousDay.plusDays(1)) ? streak + 1 : 1;
                previousDay = day;
                bestStreak = Math.max(bestStreak, streak);
            }
            for (val definition : QuizBadgeDefinition.normalize(attempt.getQuizBadges())) {
                if ((long) attempt.getScore() * 100 >= (long) attempt.getTotalQuestions() * definition.minimumPercentage()) {
                    String key = "QUIZ:" + attempt.getQuizId() + ":" + definition.id();
                    earned.putIfAbsent(key, new StudentBadgeDto.EarnedBadge(key, definition.id(), definition.name(),
                            definition.emoji(), definition.description(), "Quiz", attempt.getQuizId(),
                            attempt.getQuizTitle(), attempt.getCompletedAt()));
                }
            }
            for (val badge : Badge.values()) {
                if (progress(badge, quizzes.size(), days.size()) >= badge.target) {
                    award(earned, badge, attempt);
                }
            }
        }

        val milestones = Arrays.stream(Badge.values())
                .map(b -> new StudentBadgeDto.Milestone(b.name(), b.name, b.emoji, b.description,
                        Math.min(progress(b, quizzes.size(), days.size()), b.target),
                        b.target, earned.containsKey(b.name())))
                .toList();
        val badges = earned.values().stream()
                .sorted(Comparator.comparing(StudentBadgeDto.EarnedBadge::earnedAt).reversed()
                        .thenComparing(StudentBadgeDto.EarnedBadge::key))
                .toList();
        return new StudentBadgeDto.Collection(student.getId(), student.getFullName(), badges,
                milestones, quizzes.size(), days.size(), bestStreak);
    }

    private static int progress(Badge badge, int quizzes, int days) {
        return switch (badge) {
            case FIRST_QUIZ, FIVE_QUIZZES, TEN_QUIZZES -> quizzes;
            case THREE_DAYS, SEVEN_DAYS -> days;
        };
    }

    private static void award(Map<String, StudentBadgeDto.EarnedBadge> earned, Badge badge,
                              StudentQuizAttempt attempt) {
        String key = badge.name();
        earned.putIfAbsent(key, new StudentBadgeDto.EarnedBadge(key, badge.name(), badge.name,
                badge.emoji, badge.description, badge.category, null, null, attempt.getCompletedAt()));
    }
}
