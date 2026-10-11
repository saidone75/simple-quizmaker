package org.saidone.quizmaker.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.val;
import org.junit.jupiter.api.*;
import org.saidone.quizmaker.config.StudentAuthenticationToken;
import org.saidone.quizmaker.dto.QuizBadgeGenerationRequest;
import org.saidone.quizmaker.entity.Student;
import org.saidone.quizmaker.entity.Teacher;
import org.saidone.quizmaker.policy.TeacherAuthorizationPolicy;
import org.saidone.quizmaker.repository.QuizRepository;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class BadgeGenerationSecurityTest {
    AnnotationConfigApplicationContext context;
    OpenAiBadgeGenerationService service;
    RestClient client;
    Teacher teacher = Teacher.builder().id(UUID.randomUUID()).username("teacher").build();
    QuizBadgeGenerationRequest request = new QuizBadgeGenerationRequest("Geografia", List.of());

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(OpenAiBadgeGenerationService.class);
        client = context.getBean(RestClient.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                teacher.getUsername(), "", List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void disabledAiIsRejectedBeforeAnyHttpRequest() {
        assertThatThrownBy(() -> service.generate(request, teacher)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(client);
    }

    @Test
    void teacherCannotImpersonateAnAiEnabledColleague() {
        val colleague = Teacher.builder().id(UUID.randomUUID()).username("colleague").aiEnabled(true).build();
        assertThatThrownBy(() -> service.generate(request, colleague)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(client);
    }

    @Test
    void studentCannotGenerateBadges() {
        teacher.setAiEnabled(true);
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(
                Student.builder().id(UUID.randomUUID()).teacher(teacher).build()));
        assertThatThrownBy(() -> service.generate(request, teacher)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(client);
    }

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean RestClient client() { return mock(RestClient.class); }
        @Bean QuizRepository quizzes() { return mock(QuizRepository.class); }
        @Bean TeacherAuthorizationPolicy teacherAuthorizationPolicy(QuizRepository quizzes) {
            return new TeacherAuthorizationPolicy(quizzes);
        }
        @Bean OpenAiBadgeGenerationService service(RestClient client) {
            return new OpenAiBadgeGenerationService(new ObjectMapper(), client);
        }
    }
}
