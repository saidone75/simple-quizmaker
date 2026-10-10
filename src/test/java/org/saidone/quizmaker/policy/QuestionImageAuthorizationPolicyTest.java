package org.saidone.quizmaker.policy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.saidone.quizmaker.config.StudentAuthenticationToken;
import org.saidone.quizmaker.dto.QuestionDto;
import org.saidone.quizmaker.entity.*;
import org.saidone.quizmaker.repository.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuestionImageAuthorizationPolicyTest {

    @Mock UploadedImageRepository images;
    @Mock QuizRepository quizzes;
    @Mock TeacherRepository teachers;

    @Mock TeacherAuthorizationPolicy teacherPolicy;
    QuestionImageAuthorizationPolicy policy;

    Teacher owner = Teacher.builder().id(UUID.randomUUID()).username("owner").build();
    Teacher other = Teacher.builder().id(UUID.randomUUID()).username("other").build();
    UUID imageId = UUID.randomUUID();
    UploadedImage image;

    @BeforeEach
    void setUp() {
        policy = new QuestionImageAuthorizationPolicy(images, quizzes, teachers, teacherPolicy);
        image = new UploadedImage();
        image.setId(imageId);
        image.setTeacherId(owner.getId());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerCanReadUnsavedUpload() {
        loginTeacher(owner);
        when(images.findById(imageId)).thenReturn(Optional.of(image));
        assertThat(policy.canRead(imageId)).isTrue();
    }

    @Test
    void unrelatedTeacherCannotReadOrDeleteEvenWithKnownUuid() {
        loginTeacher(other);
        when(images.findById(imageId)).thenReturn(Optional.of(image));
        when(teacherPolicy.isTeacher(other)).thenReturn(true);
        assertThat(policy.canRead(imageId)).isFalse();
        assertThat(policy.canDelete(imageId, other)).isFalse();
    }

    @Test
    void studentCanReadImageInPublishedQuizOfOwnTeacher() {
        loginStudent(owner);
        when(quizzes.findByTeacherAndPublishedTrueAndArchivedFalseOrderByCreatedAtDesc(owner))
                .thenReturn(List.of(quiz(owner, false)));
        assertThat(policy.canRead(imageId)).isTrue();
    }

    @Test
    void studentCannotReadUnreferencedOrOtherTeachersImage() {
        loginStudent(other);
        assertThat(policy.canRead(imageId)).isFalse();
        verify(quizzes).findByTeacherAndPublishedTrueAndArchivedFalseOrderByCreatedAtDesc(other);
        verifyNoInteractions(images);
    }

    @Test
    void studentCannotDelete() {
        loginStudent(owner);
        assertThat(policy.canDelete(imageId, owner)).isFalse();
    }

    @Test
    void ownerCanDeleteOwnImage() {
        when(teacherPolicy.isTeacher(owner)).thenReturn(true);
        when(images.findById(imageId)).thenReturn(Optional.of(image));
        when(quizzes.findAll()).thenReturn(List.of(quiz(owner, false)));
        assertThat(policy.canDelete(imageId, owner)).isTrue();
    }

    @Test
    void imageReferencedByAnotherTeacherCannotBeDeleted() {
        when(teacherPolicy.isTeacher(owner)).thenReturn(true);
        when(images.findById(imageId)).thenReturn(Optional.of(image));
        when(quizzes.findAll()).thenReturn(List.of(quiz(other, true)));
        assertThat(policy.canDelete(imageId, owner)).isFalse();
    }

    @Test
    void legacyImageCanBeReadAndDeletedThroughOwnQuizReference() {
        image.setTeacherId(null);
        loginTeacher(owner);
        when(images.findById(imageId)).thenReturn(Optional.of(image));
        when(quizzes.findAllByTeacherOrderByCreatedAtDesc(owner)).thenReturn(List.of(quiz(owner, true)));
        when(teacherPolicy.isTeacher(owner)).thenReturn(true);
        when(quizzes.findAll()).thenReturn(List.of(quiz(owner, true)));
        assertThat(policy.canRead(imageId)).isTrue();
        assertThat(policy.canDelete(imageId, owner)).isTrue();
    }

    @Test
    void cannotAttachForeignImageByIdOrUrl() {
        when(images.findById(imageId)).thenReturn(Optional.of(image));
        var question = new QuestionDto();
        question.setImageId(imageId.toString());
        assertThatThrownBy(() -> policy.validateQuestions(List.of(question), other))
                .isInstanceOf(AccessDeniedException.class);
        question.setImageId(null);
        question.setImageUrl("/api/quizzes/%69mages/" + imageId);
        assertThatThrownBy(() -> policy.validateQuestions(List.of(question), other))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void ownerCanAttachOwnUploadAndExternalImagesRemainAllowed() {
        when(images.findById(imageId)).thenReturn(Optional.of(image));
        var question = new QuestionDto();
        question.setImageId(imageId.toString());
        question.setImageUrl("https://upload.wikimedia.org/example.png");
        assertThatCode(() -> policy.validateQuestions(List.of(question), owner)).doesNotThrowAnyException();
    }

    @Test
    void unauthenticatedUserCannotRead() {
        assertThat(policy.canRead(imageId)).isFalse();
        verifyNoInteractions(images, quizzes, teachers);
    }

    private Quiz quiz(Teacher teacher, boolean urlOnly) {
        var question = new Question();
        if (urlOnly) question.setImageUrl("/api/quizzes/images/" + imageId);
        else question.setImageId(imageId.toString());
        return Quiz.builder().teacher(teacher).questions(List.of(question)).published(true).build();
    }

    private void loginTeacher(Teacher teacher) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                teacher.getUsername(), "", List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
        when(teachers.findByUsernameIgnoreCase(teacher.getUsername())).thenReturn(Optional.of(teacher));
    }

    private void loginStudent(Teacher teacher) {
        SecurityContextHolder.getContext().setAuthentication(new StudentAuthenticationToken(
                Student.builder().id(UUID.randomUUID()).teacher(teacher).build()));
    }
}
