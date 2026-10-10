package org.saidone.quizmaker.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.saidone.quizmaker.dto.QuestionImageUploadDto;
import org.saidone.quizmaker.entity.Question;
import org.saidone.quizmaker.entity.Quiz;
import org.saidone.quizmaker.entity.Teacher;
import org.saidone.quizmaker.repository.QuizRepository;
import org.saidone.quizmaker.repository.TeacherRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuizSharingServiceTest {

    @Mock QuizRepository quizzes;
    @Mock TeacherRepository teachers;
    @Mock QuestionImageStorageService images;
    
    QuizSharingService sharing;
    Teacher owner = Teacher.builder().id(UUID.randomUUID()).username("owner").build();
    Teacher first = Teacher.builder().id(UUID.randomUUID()).username("first").build();
    Teacher second = Teacher.builder().id(UUID.randomUUID()).username("second").build();
    Quiz source;
    UUID imageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sharing = new QuizSharingService(quizzes, teachers, images);
        var question = new Question();
        question.setText("Domanda");
        question.setImageId(imageId.toString());
        question.setImageUrl("/api/quizzes/images/" + imageId);
        source = Quiz.builder().id(UUID.randomUUID()).title("Quiz").emoji("🧪")
                .teacher(owner).createdByUsername(owner.getUsername()).questions(List.of(question)).build();
        when(quizzes.findByIdAndTeacher(source.getId(), owner)).thenReturn(Optional.of(source));
    }

    @Test
    void eachRecipientGetsOwnImageIdAndRepeatedReferencesReuseTheirCopy() {
        source.setQuestions(List.of(source.getQuestions().getFirst(), source.getQuestions().getFirst()));
        when(teachers.findAllById(List.of(first.getId(), second.getId()))).thenReturn(List.of(first, second));
        var firstCopy = copy();
        var secondCopy = copy();
        when(images.duplicateForTeacher(imageId, first)).thenReturn(firstCopy);
        when(images.duplicateForTeacher(imageId, second)).thenReturn(secondCopy);

        assertThat(sharing.shareQuizToTeachers(source.getId(), List.of(first.getId(), second.getId()), owner)).isEqualTo(2);

        var captor = ArgumentCaptor.forClass(Quiz.class);
        verify(quizzes, times(2)).save(captor.capture());
        var shared = captor.getAllValues();
        assertThat(shared.get(0).getQuestions()).allSatisfy(q -> {
            assertThat(q.getImageId()).isEqualTo(firstCopy.getId().toString());
            assertThat(q.getImageUrl()).isEqualTo(firstCopy.getUrl());
        });
        assertThat(shared.get(1).getQuestions()).allSatisfy(q ->
                assertThat(q.getImageId()).isEqualTo(secondCopy.getId().toString()));
        verify(images).duplicateForTeacher(imageId, first);
        verify(images).duplicateForTeacher(imageId, second);
        assertThat(source.getQuestions().getFirst().getImageId()).isEqualTo(imageId.toString());
    }

    @Test
    void urlOnlyUploadIsDuplicatedAndExternalUrlIsPreserved() {
        source.getQuestions().getFirst().setImageId(null);
        var external = new Question();
        external.setImageUrl("https://upload.wikimedia.org/example.png");
        source.setQuestions(List.of(source.getQuestions().getFirst(), external));
        when(teachers.findAllById(List.of(first.getId()))).thenReturn(List.of(first));
        var copy = copy();
        when(images.duplicateForTeacher(imageId, first)).thenReturn(copy);

        sharing.shareQuizToTeachers(source.getId(), List.of(first.getId()), owner);

        var captor = ArgumentCaptor.forClass(Quiz.class);
        verify(quizzes).save(captor.capture());
        assertThat(captor.getValue().getQuestions().get(0).getImageId()).isEqualTo(copy.getId().toString());
        assertThat(captor.getValue().getQuestions().get(1).getImageUrl()).isEqualTo(external.getImageUrl());
        assertThat(captor.getValue().getQuestions().get(1).getImageId()).isNull();
    }

    @Test
    void alreadySharedQuizDoesNotCreateAdditionalImageRecords() {
        when(teachers.findAllById(List.of(first.getId()))).thenReturn(List.of(first));
        when(quizzes.existsByTitleAndTeacherAndCreatedByUsername(source.getTitle(), first, owner.getUsername())).thenReturn(true);
        assertThat(sharing.shareQuizToTeachers(source.getId(), List.of(first.getId()), owner)).isZero();
        verifyNoInteractions(images);
        verify(quizzes, never()).save(any());
    }

    private QuestionImageUploadDto copy() {
        var id = UUID.randomUUID();
        return QuestionImageUploadDto.builder().id(id).url("/api/quizzes/images/" + id).build();
    }
}
