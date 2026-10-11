package org.saidone.quizmaker.service;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.saidone.quizmaker.dto.QuizBadgeDefinition;
import org.saidone.quizmaker.dto.StudentBadgeDto;
import org.saidone.quizmaker.entity.Quiz;
import org.saidone.quizmaker.entity.Student;
import org.saidone.quizmaker.entity.StudentQuizAttempt;
import org.saidone.quizmaker.entity.Teacher;
import org.saidone.quizmaker.repository.StudentQuizAttemptRepository;
import org.saidone.quizmaker.repository.StudentRepository;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentBadgeServiceTest {
    @Mock StudentQuizAttemptRepository attempts;
    @Mock StudentRepository students;
    @Mock StudentAuthenticationService authentication;
    @InjectMocks StudentBadgeService service;

    Teacher teacher = Teacher.builder().id(UUID.randomUUID()).username("teacher").build();
    Student student = Student.builder().id(UUID.randomUUID()).teacher(teacher).fullName("Alice").build();
    Quiz quiz = Quiz.builder().id(UUID.randomUUID()).title("Geografia").teacher(teacher)
            .badges(List.of(new QuizBadgeDefinition("cartographer", "Cartografo provetto", "🗺️", 80))).build();
    LocalDateTime date = LocalDateTime.of(2026, 10, 1, 12, 0);

    @Test
    void recordsSnapshotsAndReturnsOnlyNewBadges() {
        when(attempts.findByStudentIdOrderByCompletedAtAscIdAsc(student.getId())).thenReturn(List.of());
        val newBadges = service.recordCompletion(quiz, student, 5, 5, date);
        assertThat(newBadges).extracting(StudentBadgeDto.EarnedBadge::name)
                .containsExactlyInAnyOrder("Cartografo provetto", "Primo passo");
        verify(attempts).save(argThat(a -> a.getQuizId().equals(quiz.getId())
                && a.getQuizTitle().equals("Geografia") && a.getQuizBadges().equals(quiz.getBadges())
                && a.getCompletedAt().equals(date)));
    }

    @Test
    void repeatedCompletionDoesNotAnnouncePreviouslyEarnedBadgesAgain() {
        val previous = StudentQuizAttempt.builder().id(UUID.randomUUID()).student(student)
                .quizId(quiz.getId()).quizTitle("Geografia").quizBadges(quiz.getBadges())
                .score(5).totalQuestions(5).completedAt(date).build();
        when(attempts.findByStudentIdOrderByCompletedAtAscIdAsc(student.getId())).thenReturn(List.of(previous));
        assertThat(service.recordCompletion(quiz, student, 5, 5, date.plusHours(1))).isEmpty();
        verify(attempts).save(any());
    }

    @Test
    void teacherCannotReadCollectionOfAStudentOutsideTheirClass() {
        when(students.findByIdAndTeacher(student.getId(), teacher)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.forTeacher(student.getId(), teacher))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(attempts);
    }

    @Test
    void ownCollectionUsesTheAuthenticatedStudentOnly() {
        when(authentication.getCurrentStudent()).thenReturn(student);
        when(attempts.findByStudentIdOrderByCompletedAtAscIdAsc(student.getId())).thenReturn(List.of());
        val collection = service.forCurrentStudent();
        assertThat(collection.studentId()).isEqualTo(student.getId());
        verify(attempts).findByStudentIdOrderByCompletedAtAscIdAsc(student.getId());
    }
}
