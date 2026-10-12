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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.saidone.quizmaker.dto.QuizGenerationRequestDto;
import org.saidone.quizmaker.entity.QuestionType;
import org.junit.jupiter.api.Test;
import org.saidone.quizmaker.dto.QuestionDto;
import org.saidone.quizmaker.dto.QuizDto;
import org.saidone.quizmaker.service.WikimediaImageSearchService;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiQuizGenerationServiceTest {

    @Test
    void shouldShuffleOptionsAndKeepCorrectAnswerConsistent() {
        val service = new OpenAiQuizGenerationService(null, null, null);

        val question = new QuestionDto();
        question.setText("Qual è il pianeta rosso?");
        question.setOptions(new ArrayList<>(List.of("Marte", "Venere", "Giove", "Mercurio")));
        question.setAnswer(0);

        val quiz = QuizDto.Request.builder()
                .title("Spazio")
                .emoji("🚀")
                .questions(new ArrayList<>(List.of(question)))
                .build();

        service.randomizeAnswerPositions(quiz, new Random(1));

        assertThat(question.getOptions()).containsExactly("Mercurio", "Marte", "Venere", "Giove");
        assertThat(question.getAnswer()).isEqualTo(1);
        assertThat(question.getOptions().get(question.getAnswer())).isEqualTo("Marte");
    }

    @Test
    void shouldResolveImageUrlFromEnglishKeywords() throws Exception {
        val wikimediaImageSearchService = mock(WikimediaImageSearchService.class);
        when(wikimediaImageSearchService.searchImage(any(), any())).thenReturn("https://upload.wikimedia.org/example.jpg");
        val service = new OpenAiQuizGenerationService(null, null, wikimediaImageSearchService);

        val question = new QuestionDto();
        question.setImageKeywords("red planet, mars astronomy");

        val quiz = QuizDto.Request.builder()
                .title("Spazio")
                .emoji("🚀")
                .questions(new ArrayList<>(List.of(question)))
                .build();

        service.checkGeneratedImageUrls(quiz, true, null);

        assertThat(question.getImageUrl()).isEqualTo("https://upload.wikimedia.org/example.jpg");
        assertThat(question.getImageKeywords()).isEqualTo("red planet, mars astronomy");
        verify(wikimediaImageSearchService).searchImage(eq(new String[]{"red planet", "mars astronomy"}), isNull());
    }

    @Test
    void shouldTrimLeadingAndTrailingSpacesFromImageKeywords() throws Exception {
        val wikimediaImageSearchService = mock(WikimediaImageSearchService.class);
        when(wikimediaImageSearchService.searchImage(any(), any())).thenReturn("https://upload.wikimedia.org/example.jpg");
        val service = new OpenAiQuizGenerationService(null, null, wikimediaImageSearchService);

        val question = new QuestionDto();
        question.setImageKeywords("  Kennedy Space Center  ,  LC-39B  ");

        val quiz = QuizDto.Request.builder()
                .title("Spazio")
                .emoji("🚀")
                .questions(new ArrayList<>(List.of(question)))
                .build();

        service.checkGeneratedImageUrls(quiz, true, null);

        assertThat(question.getImageKeywords()).isEqualTo("Kennedy Space Center, LC-39B");
        verify(wikimediaImageSearchService).searchImage(eq(new String[]{"Kennedy Space Center", "LC-39B"}), isNull());
    }

    @Test
    void shouldKeepKeywordOrderAndHandleSemicolonOrNumberedSeparators() throws Exception {
        val wikimediaImageSearchService = mock(WikimediaImageSearchService.class);
        when(wikimediaImageSearchService.searchImage(any(), any())).thenReturn("https://upload.wikimedia.org/example.jpg");
        val service = new OpenAiQuizGenerationService(null, null, wikimediaImageSearchService);

        val question = new QuestionDto();
        question.setImageKeywords("1) Saturn; 2) rings; 3) Cassini orbiter");

        val quiz = QuizDto.Request.builder()
                .title("Spazio")
                .emoji("🪐")
                .questions(new ArrayList<>(List.of(question)))
                .build();

        service.checkGeneratedImageUrls(quiz, true, null);

        assertThat(question.getImageKeywords()).isEqualTo("Saturn, rings, Cassini orbiter");
        verify(wikimediaImageSearchService).searchImage(eq(new String[]{"Saturn", "rings", "Cassini orbiter"}), isNull());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void schemaAndShuffleSupportSelectedOptionCount(int numberOfOptions) {
        var mapper = new ObjectMapper();
        var service = new OpenAiQuizGenerationService(mapper, null, null);
        var request = generationRequest();
        request.setNumberOfOptions(numberOfOptions);
        var payload = mapper.valueToTree(service.buildPayload(request, null));
        var properties = payload.path("response_format").path("json_schema").path("schema")
                .path("properties").path("questions").path("items").path("properties");
        assertThat(properties.path("options").path("minItems").asInt()).isEqualTo(numberOfOptions);
        assertThat(properties.path("options").path("maxItems").asInt()).isEqualTo(numberOfOptions);
        assertThat(properties.path("answer").path("maximum").asInt()).isEqualTo(numberOfOptions - 1);
        assertThat(payload.path("messages").path(1).path("content").asText())
                .contains("esattamente " + numberOfOptions + " risposte");

        var question = new QuestionDto();
        question.setOptions(new ArrayList<>(List.of("Marte", "Venere", "Giove", "Mercurio").subList(0, numberOfOptions)));
        question.setAnswer(0);
        service.randomizeAnswerPositions(QuizDto.Request.builder().questions(List.of(question)).build(), new Random(1));
        assertThat(question.getOptions()).hasSize(numberOfOptions);
        assertThat(question.getOptions().get(question.getAnswer())).isEqualTo("Marte");
    }

    @Test
    void trueFalseSchemaUsesTwoOptionsAndNeverShufflesThem() {
        var mapper = new ObjectMapper();
        var service = new OpenAiQuizGenerationService(mapper, null, null);
        var request = generationRequest();
        request.setQuestionType(QuestionType.TRUE_FALSE);
        var payload = mapper.valueToTree(service.buildPayload(request, null));
        var properties = payload.path("response_format").path("json_schema").path("schema")
                .path("properties").path("questions").path("items").path("properties");
        assertThat(properties.path("type").path("enum").path(0).asText()).isEqualTo("TRUE_FALSE");
        assertThat(properties.path("options").path("maxItems").asInt()).isEqualTo(2);
        assertThat(properties.path("answer").path("maximum").asInt()).isEqualTo(1);
        assertThat(payload.path("messages").path(1).path("content").asText()).contains("un'affermazione", "Vero", "Falso");

        var question = new QuestionDto();
        question.setType(QuestionType.TRUE_FALSE);
        question.setOptions(new ArrayList<>(List.of("Vero", "Falso")));
        question.setAnswer(1);
        service.randomizeAnswerPositions(QuizDto.Request.builder().questions(List.of(question)).build(), new Random(1));
        assertThat(question.getOptions()).containsExactly("Vero", "Falso");
        assertThat(question.getAnswer()).isEqualTo(1);

        request.setQuestionType(QuestionType.MULTIPLE_CHOICE);
        request.setNumberOfOptions(3);
        var nextPayload = mapper.valueToTree(service.buildPayload(request, null));
        assertThat(nextPayload.path("response_format").path("json_schema").path("schema")
                .path("properties").path("questions").path("items").path("properties")
                .path("options").path("maxItems").asInt()).isEqualTo(3);
    }

    private QuizGenerationRequestDto generationRequest() {
        var request = new QuizGenerationRequestDto();
        request.setTopic("Spazio");
        request.setNumberOfQuestions(3);
        request.setDifficulty("Medio");
        request.setTone("Neutro");
        return request;
    }

}
