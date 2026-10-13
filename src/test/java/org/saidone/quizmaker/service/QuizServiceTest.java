/*
 * Alice's Simple Quiz Maker - fun quizzes for curious minds
 * Copyright (C) 2026 Miss Alice & Saidone
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.saidone.quizmaker.service;

import org.saidone.quizmaker.dto.QuestionDto;
import org.saidone.quizmaker.policy.QuestionImageAuthorizationPolicy;
import org.springframework.security.access.AccessDeniedException;
import org.saidone.quizmaker.mapper.QuestionMapper;
import org.saidone.quizmaker.mapper.QuizMapper;
import org.saidone.quizmaker.entity.Question;
import org.saidone.quizmaker.entity.Teacher;
import org.saidone.quizmaker.repository.QuizRepository;
import org.saidone.quizmaker.repository.QuizSubmissionRepository;
import org.saidone.quizmaker.dto.QuizDto;
import org.saidone.quizmaker.entity.Quiz;
import lombok.val;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.saidone.quizmaker.entity.QuestionType;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuizServiceTest {

    @Mock
    private QuizRepository quizRepository;

    @Mock
    private QuizSubmissionRepository quizSubmissionRepository;

    @Mock
    private QuizMapper quizMapper;

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private QuestionImageAuthorizationPolicy questionImageAuthorizationPolicy;

    @InjectMocks
    private QuizService quizService;

    private Quiz sampleQuiz;
    private Teacher teacher;

    @Test
    void createRejectsUnauthorizedImageBeforeSaving() {
        val question = validQuestion(2);
        question.setImageId(UUID.randomUUID().toString());
        val request = QuizDto.Request.builder().title("Quiz").emoji("🧪").questions(List.of(question)).build();
        doThrow(new AccessDeniedException("Accesso immagine non consentito"))
                .when(questionImageAuthorizationPolicy).validateQuestions(request.getQuestions(), teacher);

        assertThatThrownBy(() -> quizService.create(request, teacher)).isInstanceOf(AccessDeniedException.class);
        verify(quizRepository, never()).save(any());
    }

    @Test
    void updateRejectsUnauthorizedImageBeforeChangingQuiz() {
        val question = validQuestion(2);
        question.setImageUrl("/api/quizzes/images/" + UUID.randomUUID());
        val request = QuizDto.Request.builder().title("Quiz").emoji("🧪").questions(List.of(question)).build();
        doThrow(new AccessDeniedException("Accesso immagine non consentito"))
                .when(questionImageAuthorizationPolicy).validateQuestions(request.getQuestions(), teacher);

        assertThatThrownBy(() -> quizService.update(sampleQuiz.getId(), request, teacher))
                .isInstanceOf(AccessDeniedException.class);
        verify(quizRepository, never()).save(any());
    }

    @BeforeEach
    void setUp() {
        val question = new Question();
        question.setText("question");
        question.setOptions(List.of("A", "B"));
        question.setAnswer(0);
        teacher = Teacher.builder().id(UUID.randomUUID()).username("teacher").password("pwd").build();

        sampleQuiz = Quiz.builder()
                .id(UUID.randomUUID())
                .title("Quiz di Test")
                .emoji("🧪")
                .published(true)
                .archived(false)
                .questions(List.of(question))
                .teacher(teacher)
                .build();
    }

    @Test
    void findAllForAdmin_returnsAllQuizzes() {
        val response = QuizDto.Response.builder()
                .id(sampleQuiz.getId())
                .title(sampleQuiz.getTitle())
                .emoji(sampleQuiz.getEmoji())
                .questionsCount(sampleQuiz.getQuestions().size())
                .build();
        when(quizRepository.findAllByTeacherOrderByCreatedAtDesc(teacher)).thenReturn(List.of(sampleQuiz));
        when(quizMapper.toResponse(sampleQuiz)).thenReturn(response);
        val result = quizService.findAllForAdmin(teacher);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getTitle()).isEqualTo("Quiz di Test");
    }

    @Test
    void findPublished_returnsOnlyPublishedQuizzes() {
        val response = QuizDto.Response.builder()
                .id(sampleQuiz.getId())
                .title(sampleQuiz.getTitle())
                .emoji(sampleQuiz.getEmoji())
                .published(sampleQuiz.getPublished())
                .questionsCount(sampleQuiz.getQuestions().size())
                .build();
        when(quizRepository.findByTeacherAndPublishedTrueAndArchivedFalseOrderByCreatedAtDesc(teacher)).thenReturn(List.of(sampleQuiz));
        when(quizMapper.toResponse(sampleQuiz)).thenReturn(response);

        val result = quizService.findPublishedForTeacher(teacher);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getPublished()).isTrue();
    }

    @Test
    void findById_returnsQuiz() {
        val response = QuizDto.Response.builder()
                .id(sampleQuiz.getId())
                .title(sampleQuiz.getTitle())
                .emoji(sampleQuiz.getEmoji())
                .questionsCount(sampleQuiz.getQuestions().size())
                .build();
        when(quizRepository.findByIdAndTeacher(sampleQuiz.getId(), teacher)).thenReturn(Optional.of(sampleQuiz));
        when(quizMapper.toResponse(sampleQuiz)).thenReturn(response);
        val result = quizService.findByIdForTeacher(sampleQuiz.getId(), teacher);
        assertThat(result.getId()).isEqualTo(sampleQuiz.getId());
        assertThat(result.getEmoji()).isEqualTo("🧪");
    }

    @Test
    void findPublishedById_returnsOnlyPublishedQuiz() {
        val response = QuizDto.Response.builder()
                .id(sampleQuiz.getId())
                .title(sampleQuiz.getTitle())
                .emoji(sampleQuiz.getEmoji())
                .published(sampleQuiz.getPublished())
                .questionsCount(sampleQuiz.getQuestions().size())
                .build();
        when(quizRepository.findByIdAndTeacherAndPublishedTrueAndArchivedFalse(sampleQuiz.getId(), teacher)).thenReturn(Optional.of(sampleQuiz));
        when(quizMapper.toResponse(sampleQuiz)).thenReturn(response);

        val result = quizService.findPublishedByIdForTeacher(sampleQuiz.getId(), teacher);

        assertThat(result.getPublished()).isTrue();
    }

    @Test
    void create_savesAndReturnsQuiz() {
        val response = QuizDto.Response.builder()
                .id(sampleQuiz.getId())
                .title(sampleQuiz.getTitle())
                .emoji(sampleQuiz.getEmoji())
                .questionsCount(sampleQuiz.getQuestions().size())
                .build();
        when(quizRepository.save(any(Quiz.class))).thenReturn(sampleQuiz);
        when(quizMapper.toResponse(sampleQuiz)).thenReturn(response);
        val questionDto = new QuestionDto();
        questionDto.setText("question");
        questionDto.setOptions(List.of("A", "B"));
        questionDto.setAnswer(0);
        val mappedQuestion = new Question();
        mappedQuestion.setText("question");
        mappedQuestion.setOptions(List.of("A", "B"));
        mappedQuestion.setAnswer(0);
        when(questionMapper.toEntity(questionDto)).thenReturn(mappedQuestion);
        val request = QuizDto.Request.builder()
                .title("Quiz di Test")
                .emoji("🧪")
                .questions(List.of(questionDto))
                .build();
        val result = quizService.create(request, teacher);
        assertThat(result.getTitle()).isEqualTo("Quiz di Test");
        verify(quizRepository, times(1)).save(any(Quiz.class));
    }

    @Test
    void delete_callsRepository() {
        when(quizRepository.findByIdAndTeacher(sampleQuiz.getId(), teacher)).thenReturn(Optional.of(sampleQuiz));
        quizService.delete(sampleQuiz.getId(), teacher);
        verify(quizSubmissionRepository, times(1)).deleteAllByQuizIdAndQuizTeacher(sampleQuiz.getId(), teacher);
        verify(quizRepository, times(1)).delete(sampleQuiz);
    }

    @Test
    void updatePublicationStatus_updatesAndReturnsQuiz() {
        val response = QuizDto.Response.builder()
                .id(sampleQuiz.getId())
                .title(sampleQuiz.getTitle())
                .emoji(sampleQuiz.getEmoji())
                .published(false)
                .questionsCount(sampleQuiz.getQuestions().size())
                .build();

        when(quizRepository.findByIdAndTeacher(sampleQuiz.getId(), teacher)).thenReturn(Optional.of(sampleQuiz));
        when(quizRepository.save(any(Quiz.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(quizMapper.toResponse(any(Quiz.class))).thenReturn(response);

        val result = quizService.updatePublicationStatus(sampleQuiz.getId(), false, teacher);

        assertThat(result.getPublished()).isFalse();
        assertThat(sampleQuiz.getPublished()).isFalse();
        verify(quizRepository, times(1)).save(sampleQuiz);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void acceptsUniformMultipleChoiceQuiz(int numberOfOptions) {
        val question = validQuestion(numberOfOptions);
        val request = QuizDto.Request.builder().questions(List.of(question, validQuestion(numberOfOptions))).build();
        when(quizRepository.save(any())).thenReturn(sampleQuiz);
        when(quizMapper.toResponse(sampleQuiz)).thenReturn(QuizDto.Response.builder().build());

        quizService.create(request, teacher);

        verify(quizRepository).save(any());
    }

    @Test
    void acceptsTrueFalseQuizAndPreservesAnswer() {
        val question = validQuestion(2);
        question.setType(QuestionType.TRUE_FALSE);
        question.setOptions(List.of("Vero", "Falso"));
        question.setAnswer(1);
        val request = QuizDto.Request.builder().questions(List.of(question)).build();
        when(quizRepository.save(any())).thenReturn(sampleQuiz);
        when(quizMapper.toResponse(sampleQuiz)).thenReturn(QuizDto.Response.builder().build());

        quizService.create(request, teacher);

        verify(questionMapper).toEntity(question);
        assertThat(question.getAnswer()).isEqualTo(1);
    }

    @Test
    void rejectsMixedFormatsBeforeSavingOrUpdating() {
        val first = validQuestion(2);
        val second = validQuestion(3);
        val request = QuizDto.Request.builder().questions(List.of(first, second)).build();
        assertThatThrownBy(() -> quizService.create(request, teacher)).hasMessageContaining("stesso tipo");
        assertThatThrownBy(() -> quizService.update(sampleQuiz.getId(), request, teacher)).hasMessageContaining("stesso tipo");

        second.setOptions(List.of("Vero", "Falso"));
        second.setType(QuestionType.TRUE_FALSE);
        assertThatThrownBy(() -> quizService.create(request, teacher)).hasMessageContaining("stesso tipo");
        verify(quizRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void rejectsUnsupportedOptionCounts(int numberOfOptions) {
        val request = QuizDto.Request.builder().questions(List.of(validQuestion(numberOfOptions))).build();
        assertThatThrownBy(() -> quizService.create(request, teacher)).isInstanceOf(IllegalArgumentException.class);
        verify(quizRepository, never()).save(any());
    }

    @Test
    void rejectsInvalidAnswerAndBlankOptions() {
        val question = validQuestion(2);
        val request = QuizDto.Request.builder().questions(List.of(question)).build();
        question.setAnswer(2);
        assertThatThrownBy(() -> quizService.create(request, teacher)).isInstanceOf(IllegalArgumentException.class);
        question.setAnswer(0);
        question.setOptions(List.of("Prima", " "));
        assertThatThrownBy(() -> quizService.create(request, teacher)).isInstanceOf(IllegalArgumentException.class);
        question.setType(QuestionType.TRUE_FALSE);
        question.setOptions(List.of("Falso", "Vero"));
        assertThatThrownBy(() -> quizService.create(request, teacher)).isInstanceOf(IllegalArgumentException.class);
        verify(quizRepository, never()).save(any());
    }

    @Test
    void rejectsMissingQuestionsAndNullQuestionBeforeImageValidation() {
        val request = QuizDto.Request.builder().build();
        assertThatThrownBy(() -> quizService.create(request, teacher)).isInstanceOf(IllegalArgumentException.class);
        request.setQuestions(List.of());
        assertThatThrownBy(() -> quizService.create(request, teacher)).isInstanceOf(IllegalArgumentException.class);
        request.setQuestions(java.util.Arrays.asList((QuestionDto) null));
        assertThatThrownBy(() -> quizService.create(request, teacher)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(questionImageAuthorizationPolicy, quizRepository);
    }

    private QuestionDto validQuestion(int numberOfOptions) {
        val question = new QuestionDto();
        question.setText("Domanda");
        question.setOptions(java.util.stream.IntStream.range(0, numberOfOptions)
                .mapToObj(index -> "Risposta " + index).toList());
        question.setAnswer(0);
        return question;
    }

}
