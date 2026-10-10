package org.saidone.quizmaker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.saidone.quizmaker.dto.StudentBadgeDto;
import org.saidone.quizmaker.entity.*;
import org.saidone.quizmaker.repository.QuizRepository;
import org.saidone.quizmaker.repository.QuizSubmissionRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuizSubmissionServiceTest {
    @Mock QuizRepository quizzes;
    @Mock QuizSubmissionRepository submissions;
    @Mock StudentBadgeService badges;
    @InjectMocks QuizSubmissionService service;

    Teacher teacher = Teacher.builder().id(UUID.randomUUID()).build();
    Student student = Student.builder().id(UUID.randomUUID()).teacher(teacher).build();
    Quiz quiz = Quiz.builder().id(UUID.randomUUID()).title("Geografia").teacher(teacher)
            .questions(List.of(question(1), question(0))).build();

    private static Question question(int answer) {
        var question = new Question();
        question.setAnswer(answer);
        return question;
    }

    @BeforeEach
    void setUp() {
        when(quizzes.findByIdAndTeacherAndPublishedTrueAndArchivedFalse(quiz.getId(), teacher))
                .thenReturn(Optional.of(quiz));
    }

    @Test
    void badgesUseServerCalculatedScoreAndAreIncludedInTheSubmissionResponse() {
        var earned = new StudentBadgeDto.EarnedBadge("key", "cartographer", "Cartografo provetto", "🗺️",
                "Risposte corrette", "Quiz", quiz.getId(), quiz.getTitle(), LocalDateTime.now());
        when(badges.recordCompletion(eq(quiz), eq(student), eq(1), eq(2), any()))
                .thenReturn(List.of(earned));

        var response = service.submit(quiz.getId(), student, List.of(1, 1));

        assertThat(response.getScore()).isEqualTo(1);
        assertThat(response.getNewBadges()).containsExactly(earned);
        verify(submissions).save(argThat(s -> s.getScore() == 1 && s.getSubmittedAt() != null));
    }

    @Test
    void lockedSubmissionCannotEarnBadges() {
        when(submissions.findByStudentIdAndQuizId(student.getId(), quiz.getId()))
                .thenReturn(Optional.of(QuizSubmission.builder().unlocked(false).build()));
        assertThatThrownBy(() -> service.submit(quiz.getId(), student, List.of(1, 0)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(badges);
        verify(submissions, never()).save(any());
    }

    @Test
    void unlockedRetryUpdatesItsDateAndRecordsAnotherAttempt() {
        var previous = QuizSubmission.builder().id(UUID.randomUUID()).student(student).quiz(quiz)
                .submittedAt(LocalDateTime.of(2026, 1, 1, 12, 0)).unlocked(true).build();
        when(submissions.findByStudentIdAndQuizId(student.getId(), quiz.getId()))
                .thenReturn(Optional.of(previous));
        when(badges.recordCompletion(eq(quiz), eq(student), eq(2), eq(2), any())).thenReturn(List.of());
        service.submit(quiz.getId(), student, List.of(1, 0));
        assertThat(previous.getSubmittedAt()).isAfter(LocalDateTime.of(2026, 1, 1, 12, 0));
        assertThat(previous.getUnlocked()).isFalse();
        verify(badges).recordCompletion(eq(quiz), eq(student), eq(2), eq(2), eq(previous.getSubmittedAt()));
    }

    @Test
    void failedSubmissionSaveDoesNotRecordAnAttempt() {
        when(submissions.save(any())).thenThrow(new IllegalStateException("save failed"));
        assertThatThrownBy(() -> service.submit(quiz.getId(), student, List.of(1, 0)))
                .hasMessage("save failed");
        verifyNoInteractions(badges);
    }
}
