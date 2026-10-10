package org.saidone.quizmaker.service;

import org.junit.jupiter.api.*;
import org.saidone.quizmaker.config.StudentAuthenticationToken;
import org.saidone.quizmaker.entity.*;
import org.saidone.quizmaker.policy.TeacherAuthorizationPolicy;
import org.saidone.quizmaker.repository.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class StudentBadgeSecurityTest {
    AnnotationConfigApplicationContext context;
    StudentBadgeService service;
    StudentQuizAttemptRepository attempts;
    StudentRepository students;
    StudentAuthenticationService authentication;
    Teacher teacher = Teacher.builder().id(UUID.randomUUID()).username("teacher").build();
    Student student = Student.builder().id(UUID.randomUUID()).teacher(teacher).fullName("Alice").build();

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(StudentBadgeService.class);
        attempts = context.getBean(StudentQuizAttemptRepository.class);
        students = context.getBean(StudentRepository.class);
        authentication = context.getBean(StudentAuthenticationService.class);
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void studentCannotImpersonateAnotherStudentToAwardBadges() {
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(student));
        var other = Student.builder().id(UUID.randomUUID()).teacher(teacher).build();
        assertThatThrownBy(() -> service.recordCompletion(Quiz.builder().build(), other, 1, 1, LocalDateTime.now()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(attempts);
    }

    @Test
    void studentCannotUseTeacherCollectionEndpoint() {
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(student));
        assertThatThrownBy(() -> service.forTeacher(student.getId(), teacher)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(attempts, students);
    }

    @Test
    void teacherCannotImpersonateAColleagueOrAwardBadgesDirectly() {
        teacherLogin();
        var colleague = Teacher.builder().id(UUID.randomUUID()).username("colleague").build();
        assertThatThrownBy(() -> service.forTeacher(student.getId(), colleague)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.recordCompletion(Quiz.builder().build(), student, 1, 1, LocalDateTime.now()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(service::forCurrentStudent).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(attempts, students);
    }

    @Test
    void authenticatedStudentCanReadTheirOwnCollection() {
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(student));
        when(authentication.getCurrentStudent()).thenReturn(student);
        service.forCurrentStudent();
        verify(attempts).findByStudentIdOrderByCompletedAtAscIdAsc(student.getId());
    }

    @Test
    void teacherCanReadAStudentInTheirOwnClass() {
        teacherLogin();
        when(students.findByIdAndTeacher(student.getId(), teacher)).thenReturn(Optional.of(student));
        service.forTeacher(student.getId(), teacher);
        verify(attempts).findByStudentIdOrderByCompletedAtAscIdAsc(student.getId());
    }

    @Test
    void authenticatedStudentCanRecordTheirOwnCompletion() {
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(student));
        var quiz = Quiz.builder().id(UUID.randomUUID()).title("Geografia").teacher(teacher).build();
        service.recordCompletion(quiz, student, 1, 1, LocalDateTime.now());
        verify(attempts).save(any());
    }

    private void teacherLogin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                teacher.getUsername(), "", List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean StudentQuizAttemptRepository attempts() { return mock(StudentQuizAttemptRepository.class); }
        @Bean StudentRepository students() { return mock(StudentRepository.class); }
        @Bean StudentAuthenticationService authentication() { return mock(StudentAuthenticationService.class); }
        @Bean QuizRepository quizzes() { return mock(QuizRepository.class); }
        @Bean TeacherAuthorizationPolicy teacherAuthorizationPolicy(QuizRepository quizzes) {
            return new TeacherAuthorizationPolicy(quizzes);
        }
        @Bean StudentBadgeService badges(StudentQuizAttemptRepository attempts, StudentRepository students,
                                         StudentAuthenticationService authentication) {
            return new StudentBadgeService(attempts, students, authentication);
        }
    }
}
