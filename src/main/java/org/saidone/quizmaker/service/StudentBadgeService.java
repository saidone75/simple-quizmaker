package org.saidone.quizmaker.service;

import lombok.RequiredArgsConstructor;
import org.saidone.quizmaker.dto.StudentBadgeDto;
import org.saidone.quizmaker.dto.QuizBadgeDefinition;
import org.saidone.quizmaker.entity.Quiz;
import org.saidone.quizmaker.entity.Student;
import org.saidone.quizmaker.entity.StudentQuizAttempt;
import org.saidone.quizmaker.entity.Teacher;
import org.saidone.quizmaker.repository.StudentQuizAttemptRepository;
import org.saidone.quizmaker.repository.StudentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StudentBadgeService {
    private final StudentQuizAttemptRepository attemptRepository;
    private final StudentRepository studentRepository;
    private final StudentAuthenticationService studentAuthenticationService;

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('STUDENT')")
    public StudentBadgeDto.Collection forCurrentStudent() {
        var student = studentAuthenticationService.getCurrentStudent();
        return collection(student);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@teacherAuthorizationPolicy.isTeacher(#teacher)")
    public StudentBadgeDto.Collection forTeacher(UUID studentId, Teacher teacher) {
        var student = studentRepository.findByIdAndTeacher(studentId, teacher)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Studente non trovato"));
        return collection(student);
    }

    /** Called inside the submission transaction: failed submissions cannot earn badges. */
    @Transactional
    @PreAuthorize("hasRole('STUDENT') && #student.id == authentication.principal.id")
    public List<StudentBadgeDto.EarnedBadge> recordCompletion(Quiz quiz, Student student,
                                                             int score, int total, LocalDateTime completedAt) {
        var history = new ArrayList<>(attemptRepository.findByStudentIdOrderByCompletedAtAscIdAsc(student.getId()));
        var previousKeys = StudentBadgeEvaluator.evaluate(student, history).badges().stream()
                .map(StudentBadgeDto.EarnedBadge::key).collect(Collectors.toSet());
        var attempt = StudentQuizAttempt.builder()
                .id(UUID.randomUUID()).student(student).quizId(quiz.getId()).quizTitle(quiz.getTitle())
                .quizBadges(QuizBadgeDefinition.normalize(quiz.getBadges()))
                .score(score).totalQuestions(total).completedAt(completedAt).build();
        attemptRepository.save(attempt);
        history.add(attempt);
        return StudentBadgeEvaluator.evaluate(student, history).badges().stream()
                .filter(badge -> !previousKeys.contains(badge.key())).toList();
    }

    private StudentBadgeDto.Collection collection(Student student) {
        return StudentBadgeEvaluator.evaluate(student,
                attemptRepository.findByStudentIdOrderByCompletedAtAscIdAsc(student.getId()));
    }
}
