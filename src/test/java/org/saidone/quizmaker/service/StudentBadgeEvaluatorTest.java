package org.saidone.quizmaker.service;

import org.junit.jupiter.api.Test;
import org.saidone.quizmaker.dto.QuizBadgeDefinition;
import org.saidone.quizmaker.dto.StudentBadgeDto;
import org.saidone.quizmaker.entity.Student;
import org.saidone.quizmaker.entity.StudentQuizAttempt;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StudentBadgeEvaluatorTest {
    private final Student student = Student.builder().id(UUID.randomUUID()).fullName("Alice").build();
    private final UUID quizId = UUID.randomUUID();
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 1, 12, 0);

    @Test
    void aPerfectQuizEarnsBothDefaultBadgesAndFirstStep() {
        var result = evaluate(List.of(attempt(quizId, 5, 5, start, null)));
        assertThat(result.badges()).extracting(StudentBadgeDto.EarnedBadge::code)
                .containsExactlyInAnyOrder("QUIZ_STAR", "QUIZ_PERFECT", "FIRST_QUIZ");
        assertThat(result.completedQuizzes()).isEqualTo(1);
        assertThat(result.activeDays()).isEqualTo(1);
    }

    @Test
    void thresholdsUseExactPercentagesWithoutRoundingUp() {
        var result = evaluate(List.of(attempt(quizId, 3, 4, start, null)));
        assertThat(result.badges()).extracting(StudentBadgeDto.EarnedBadge::code).containsExactly("FIRST_QUIZ");
        result = evaluate(List.of(attempt(quizId, 4, 5, start, null)));
        assertThat(result.badges()).extracting(StudentBadgeDto.EarnedBadge::code)
                .containsExactlyInAnyOrder("QUIZ_STAR", "FIRST_QUIZ");
    }

    @Test
    void themedBadgesUseTheirOwnNamesAndThresholds() {
        var definitions = List.of(new QuizBadgeDefinition("cartographer", "Cartografo provetto", "🗺️", 70),
                new QuizBadgeDefinition("explorer", "Grande esploratore", "🧭", 100));
        var partial = evaluate(List.of(attempt(quizId, 7, 10, start, definitions)));
        assertThat(partial.badges()).extracting(StudentBadgeDto.EarnedBadge::name)
                .contains("Cartografo provetto").doesNotContain("Grande esploratore", "Stella del quiz");
        var perfect = evaluate(List.of(attempt(quizId, 10, 10, start, definitions)));
        assertThat(perfect.badges()).extracting(StudentBadgeDto.EarnedBadge::name)
                .contains("Cartografo provetto", "Grande esploratore");
    }

    @Test
    void effortBadgeRewardsCompletionEvenWhenAllAnswersAreWrong() {
        var result = evaluate(List.of(attempt(quizId, 0, 5, start,
                List.of(new QuizBadgeDefinition("effort", "Piccolo viaggiatore", "🌱", 0)))));
        assertThat(result.badges()).extracting(StudentBadgeDto.EarnedBadge::name)
                .containsExactlyInAnyOrder("Piccolo viaggiatore", "Primo passo");
    }

    @Test
    void earnedBadgeKeepsItsOriginalSnapshotAndDateAfterQuizEditsAndPoorerRetries() {
        var original = List.of(new QuizBadgeDefinition("geography", "Cartografo provetto", "🗺️", 80));
        var changed = List.of(new QuizBadgeDefinition("geography", "Nuovo nome", "🌍", 50));
        var result = evaluate(List.of(attempt(quizId, 5, 5, start, original),
                attempt(quizId, 4, 5, start.plusDays(1), changed),
                attempt(quizId, 0, 5, start.plusDays(2), List.of())));
        assertThat(result.badges().stream().filter(b -> b.quizId() != null).toList())
                .singleElement().satisfies(b -> {
                    assertThat(b.name()).isEqualTo("Cartografo provetto");
                    assertThat(b.earnedAt()).isEqualTo(start);
                    assertThat(b.quizTitle()).isEqualTo("Geografia");
                });
        assertThat(result.completedQuizzes()).isEqualTo(1);
    }

    @Test
    void badgesAreUniquePerQuizAndSeparateBetweenQuizzes() {
        var result = evaluate(List.of(attempt(quizId, 5, 5, start, null),
                attempt(quizId, 5, 5, start.plusHours(1), null),
                attempt(UUID.randomUUID(), 5, 5, start.plusHours(2), null)));
        assertThat(result.badges().stream().filter(b -> b.quizId() != null)).hasSize(4);
        assertThat(result.badges()).extracting(StudentBadgeDto.EarnedBadge::key).doesNotHaveDuplicates();
        assertThat(result.completedQuizzes()).isEqualTo(2);
        assertThat(result.activeDays()).isEqualTo(1);
        assertThat(result.bestStreak()).isEqualTo(1);
    }

    @Test
    void retriesCountForDaysButNotForDistinctQuizMilestones() {
        var history = IntStream.range(0, 7).mapToObj(i -> attempt(quizId, 0, 5, start.plusDays(i), null)).toList();
        var result = evaluate(history);
        assertThat(result.completedQuizzes()).isEqualTo(1);
        assertThat(result.activeDays()).isEqualTo(7);
        assertThat(result.bestStreak()).isEqualTo(7);
        assertThat(result.badges()).extracting(StudentBadgeDto.EarnedBadge::code)
                .contains("FIRST_QUIZ", "THREE_DAYS", "SEVEN_DAYS")
                .doesNotContain("FIVE_QUIZZES", "TEN_QUIZZES", "QUIZ_STAR", "STREAK_THREE", "STREAK_SEVEN");
    }

    @Test
    void nonConsecutiveDaysEarnConsistencyBadgesAndDuplicateDaysCountOnce() {
        var history = new ArrayList<StudentQuizAttempt>();
        for (int day : new int[]{0, 0, 1, 3, 4, 5, 20}) {
            history.add(attempt(UUID.randomUUID(), 0, 5, start.plusDays(day), null));
        }
        // Intentionally reverse the history: awards must use chronological evidence.
        var result = evaluate(history.reversed());
        assertThat(result.activeDays()).isEqualTo(6);
        assertThat(result.bestStreak()).isEqualTo(3);
        assertThat(result.badges().stream().filter(b -> b.code().equals("THREE_DAYS")))
                .singleElement().satisfies(b -> assertThat(b.earnedAt()).isEqualTo(start.plusDays(3)));
        assertThat(result.badges()).extracting(StudentBadgeDto.EarnedBadge::code)
                .doesNotContain("STREAK_THREE", "STREAK_SEVEN");
        assertThat(result.milestones()).extracting(StudentBadgeDto.Milestone::code)
                .containsExactly("FIRST_QUIZ", "FIVE_QUIZZES", "TEN_QUIZZES", "THREE_DAYS", "SEVEN_DAYS");
    }

    @Test
    void tenDistinctQuizzesUnlockBothJourneyMilestones() {
        var result = evaluate(IntStream.range(0, 10)
                .mapToObj(i -> attempt(UUID.randomUUID(), 0, 5, start.plusHours(i), List.of())).toList());
        assertThat(result.badges()).extracting(StudentBadgeDto.EarnedBadge::code)
                .containsExactlyInAnyOrder("FIRST_QUIZ", "FIVE_QUIZZES", "TEN_QUIZZES");
        assertThat(result.milestones().stream().filter(m -> m.code().equals("TEN_QUIZZES")))
                .singleElement().satisfies(m -> {
                    assertThat(m.progress()).isEqualTo(10);
                    assertThat(m.earned()).isTrue();
                });
    }

    @Test
    void emptyAndInvalidQuizzesDoNotEarnBadgesOrCountTowardsMilestones() {
        var result = evaluate(List.of(attempt(quizId, 0, 0, start, null),
                attempt(quizId, -1, 5, start, null), attempt(quizId, 6, 5, start, null)));
        assertThat(result.badges()).isEmpty();
        assertThat(result.completedQuizzes()).isZero();
        assertThat(result.milestones()).allSatisfy(m -> assertThat(m.progress()).isZero());
    }

    private StudentBadgeDto.Collection evaluate(List<StudentQuizAttempt> history) {
        return StudentBadgeEvaluator.evaluate(student, history);
    }

    private StudentQuizAttempt attempt(UUID quiz, int score, int total, LocalDateTime at,
                                       List<QuizBadgeDefinition> definitions) {
        return StudentQuizAttempt.builder().id(UUID.randomUUID()).student(student).quizId(quiz)
                .quizTitle("Geografia").score(score).totalQuestions(total).completedAt(at).quizBadges(definitions).build();
    }
}
