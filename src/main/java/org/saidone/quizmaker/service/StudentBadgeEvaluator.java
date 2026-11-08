package org.saidone.quizmaker.service;

import lombok.val;

import org.saidone.quizmaker.dto.StudentBadgeDto;
import org.saidone.quizmaker.dto.QuizBadgeDefinition;
import org.saidone.quizmaker.dto.ClassBadgeRule;
import org.saidone.quizmaker.entity.Student;
import org.saidone.quizmaker.entity.StudentQuizAttempt;

import java.time.LocalDate;
import java.util.*;

/** Awards are derived from immutable attempts, so an earned badge is never revoked. */
public final class StudentBadgeEvaluator {
    private StudentBadgeEvaluator() { }

    private enum Badge {
        FIRST_QUIZ("Primo passo", "🌱", "Percorso"),
        FIVE_QUIZZES("Esploratore", "🧭", "Percorso"),
        TEN_QUIZZES("Grande esploratore", "🚀", "Percorso"),
        THREE_DAYS("Presenza costante", "📅", "Costanza"),
        SEVEN_DAYS("Con impegno", "💪", "Costanza");

        final String name;
        final String emoji;
        final String category;

        Badge(String name, String emoji, String category) {
            this.name = name;
            this.emoji = emoji;
            this.category = category;
        }
    }

    public static StudentBadgeDto.Collection evaluate(Student student, List<StudentQuizAttempt> history) {
        return evaluate(student, history, ClassBadgeRule.defaults());
    }

    public static List<SettingRow> settings(List<ClassBadgeRule> rules) {
        return ClassBadgeRule.normalize(rules).stream().map(rule -> {
            val badge = Badge.valueOf(rule.id());
            return new SettingRow(rule.id(), badge.name, badge.emoji, badge.category,
                    rule.enabled(), rule.target());
        }).toList();
    }

    public record SettingRow(String id, String name, String emoji, String category, boolean enabled, int target) {}

    public static StudentBadgeDto.Collection evaluate(Student student, List<StudentQuizAttempt> history,
                                                       List<ClassBadgeRule> currentRules) {
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
            for (val rule : ClassBadgeRule.normalize(attempt.getClassBadgeRules())) {
                val badge = Badge.valueOf(rule.id());
                if (rule.enabled() && progress(badge, quizzes.size(), days.size()) >= rule.target()) {
                    award(earned, badge, rule.target(), attempt);
                }
            }
        }

        val milestones = ClassBadgeRule.normalize(currentRules).stream().filter(ClassBadgeRule::enabled)
                .map(rule -> {
                    val b = Badge.valueOf(rule.id());
                    return new StudentBadgeDto.Milestone(b.name(), b.name, b.emoji, description(b, rule.target()),
                        Math.min(progress(b, quizzes.size(), days.size()), rule.target()),
                        rule.target(), earned.containsKey(b.name()));
                })
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

    private static String description(Badge badge, int target) {
        return badge.category.equals("Percorso")
                ? "Completa " + target + (target == 1 ? " quiz." : " quiz diversi.")
                : "Completa quiz in " + target + (target == 1 ? " giorno." : " giorni diversi.");
    }

    private static void award(Map<String, StudentBadgeDto.EarnedBadge> earned, Badge badge, int target,
                              StudentQuizAttempt attempt) {
        String key = badge.name();
        earned.putIfAbsent(key, new StudentBadgeDto.EarnedBadge(key, badge.name(), badge.name,
                badge.emoji, description(badge, target), badge.category, null, null, attempt.getCompletedAt()));
    }
}
