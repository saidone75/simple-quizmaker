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

package org.saidone.quizmaker.service.ai;

import lombok.val;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.saidone.quizmaker.dto.QuestionDto;
import org.saidone.quizmaker.dto.QuizDto;
import org.saidone.quizmaker.dto.QuizGenerationRequestDto;
import org.saidone.quizmaker.entity.QuestionType;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuizGenerationApplicationServiceTest {
    private QuizGenerationService generator;
    private QuizGenerationApplicationService service;
    private QuizGenerationRequestDto request;

    @BeforeEach
    void setUp() {
        generator = mock(QuizGenerationService.class);
        service = new QuizGenerationApplicationService(generator);
        ReflectionTestUtils.setField(service, "maxQuestionsPerRequest", 20);
        ReflectionTestUtils.setField(service, "maxAttempts", 1);
        request = new QuizGenerationRequestDto();
        request.setTopic("Scienze");
        request.setNumberOfQuestions(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void acceptsRequestedNumberOfOptions(int numberOfOptions) {
        request.setNumberOfOptions(numberOfOptions);
        val question = question(numberOfOptions);
        when(generator.generateQuiz(request, null))
                .thenReturn(QuizDto.Request.builder().questions(List.of(question)).build());
        assertThat(service.generateQuiz(request, null).getQuestions()).containsExactly(question);
    }

    @Test
    void acceptsTrueFalseQuiz() {
        request.setQuestionType(QuestionType.TRUE_FALSE);
        val question = question(2);
        question.setType(QuestionType.TRUE_FALSE);
        question.setOptions(List.of("Vero", "Falso"));
        question.setAnswer(1);
        when(generator.generateQuiz(request, null))
                .thenReturn(QuizDto.Request.builder().questions(List.of(question)).build());
        assertThat(service.generateQuiz(request, null).getQuestions()).containsExactly(question);
    }

    @Test
    void rejectsAnswersThatDoNotMatchRequestedFormat() {
        request.setNumberOfOptions(2);
        val question = question(3);
        when(generator.generateQuiz(request, null))
                .thenAnswer(invocation -> QuizDto.Request.builder().questions(List.of(question)).build());
        assertThatThrownBy(() -> service.generateQuiz(request, null)).isInstanceOf(IllegalStateException.class);
        request.setQuestionType(QuestionType.TRUE_FALSE);
        question.setOptions(List.of("Vero", "Falso"));
        assertThatThrownBy(() -> service.generateQuiz(request, null)).isInstanceOf(IllegalStateException.class);
        question.setType(QuestionType.TRUE_FALSE);
        question.setOptions(List.of("Falso", "Vero"));
        assertThatThrownBy(() -> service.generateQuiz(request, null)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void rejectsUnsupportedRequestedOptionCountBeforeCallingAi(int numberOfOptions) {
        request.setNumberOfOptions(numberOfOptions);
        assertThatThrownBy(() -> service.generateQuiz(request, null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(generator);
    }

    private QuestionDto question(int numberOfOptions) {
        val question = new QuestionDto();
        question.setText("Il Sole è una stella?");
        question.setEmoji("☀️");
        question.setFeedback("Il Sole è la stella del nostro sistema.");
        question.setOptions(IntStream.range(0, numberOfOptions).mapToObj(i -> "Risposta " + i).toList());
        question.setAnswer(0);
        return question;
    }
}
