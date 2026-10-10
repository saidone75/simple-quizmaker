package org.saidone.quizmaker.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.saidone.quizmaker.entity.Teacher;
import org.saidone.quizmaker.entity.UploadedImage;
import org.saidone.quizmaker.policy.QuestionImageAuthorizationPolicy;
import org.saidone.quizmaker.policy.TeacherAuthorizationPolicy;
import org.saidone.quizmaker.repository.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class QuestionImageSecurityTest {

    AnnotationConfigApplicationContext context;
    QuestionImageStorageService storage;
    UploadedImageRepository images;
    TeacherRepository teachers;

    Teacher owner = Teacher.builder().id(UUID.randomUUID()).username("owner").build();
    Teacher other = Teacher.builder().id(UUID.randomUUID()).username("other").build();
    UUID imageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        storage = context.getBean(QuestionImageStorageService.class);
        images = context.getBean(UploadedImageRepository.class);
        teachers = context.getBean(TeacherRepository.class);
        var image = new UploadedImage();
        image.setId(imageId);
        image.setTeacherId(owner.getId());
        image.setFilePath("/must-not-be-accessed");
        when(images.findById(imageId)).thenReturn(Optional.of(image));
        when(teachers.findByUsernameIgnoreCase(other.getUsername())).thenReturn(Optional.of(other));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                other.getUsername(), "", List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void methodSecurityRejectsForeignDownloadBeforeFileAccess() {
        assertThatThrownBy(() -> storage.load(imageId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> storage.resolveMediaType(imageId)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void methodSecurityRejectsForeignDeletion() {
        assertThatThrownBy(() -> storage.deleteForTeacher(imageId, other)).isInstanceOf(AccessDeniedException.class);
        verify(images, never()).delete(any());
    }

    @Test
    void callerCannotImpersonateOwner() {
        assertThatThrownBy(() -> storage.deleteForTeacher(imageId, owner)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> storage.store(null, owner)).isInstanceOf(AccessDeniedException.class);
        verify(images, never()).delete(any());
        verify(images, never()).save(any());
    }

    @Test
    void nonAdminCannotDuplicateImages() {
        assertThatThrownBy(() -> storage.duplicateForTeacher(imageId, other))
                .isInstanceOf(AccessDeniedException.class);
        verify(images, never()).save(any());
    }

    @Test
    void adminCannotDuplicateImagesTheyCannotRead() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                other.getUsername(), "", List.of(new SimpleGrantedAuthority("ROLE_TEACHER"),
                new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertThatThrownBy(() -> storage.duplicateForTeacher(imageId, other))
                .isInstanceOf(AccessDeniedException.class);
        verify(images, never()).save(any());
    }

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean UploadedImageRepository images() { return mock(UploadedImageRepository.class); }
        @Bean QuizRepository quizzes() { return mock(QuizRepository.class); }
        @Bean TeacherRepository teachers() { return mock(TeacherRepository.class); }
        @Bean TeacherAuthorizationPolicy teacherAuthorizationPolicy(QuizRepository quizzes) {
            return new TeacherAuthorizationPolicy(quizzes);
        }
        @Bean QuestionImageAuthorizationPolicy questionImageAuthorizationPolicy(UploadedImageRepository images,
                QuizRepository quizzes, TeacherRepository teachers, TeacherAuthorizationPolicy teacherPolicy) {
            return new QuestionImageAuthorizationPolicy(images, quizzes, teachers, teacherPolicy);
        }
        @Bean QuestionImageStorageService storage(UploadedImageRepository images) {
            return new QuestionImageStorageService(images);
        }
    }
}
