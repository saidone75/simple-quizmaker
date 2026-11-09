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
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.saidone.quizmaker.dto.QuestionDto;
import org.saidone.quizmaker.dto.QuizDto;
import org.saidone.quizmaker.service.WikimediaImageSearchService;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiQuizGenerationServiceTest {

    private final List<OpenAiQuizGenerationService> services = new ArrayList<>();

    @AfterEach
    void shutdownExecutors() {
        services.forEach(OpenAiQuizGenerationService::shutdownWikimediaSearchExecutor);
    }

    private OpenAiQuizGenerationService createService(ObjectMapper mapper, RestClient client,
                                                       WikimediaImageSearchService imageSearch) {
        val service = new OpenAiQuizGenerationService(mapper, client, imageSearch);
        services.add(service);
        return service;
    }

    @Test
    void shouldShuffleOptionsAndKeepCorrectAnswerConsistent() {
        val service = createService(null, null, null);

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
        val service = createService(null, null, wikimediaImageSearchService);

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
        val service = createService(null, null, wikimediaImageSearchService);

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
        val service = createService(null, null, wikimediaImageSearchService);

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
        val mapper = new ObjectMapper();
        val service = createService(mapper, null, null);
        val request = generationRequest();
        request.setNumberOfOptions(numberOfOptions);
        val payload = mapper.valueToTree(service.buildPayload(request, null));
        val properties = payload.path("response_format").path("json_schema").path("schema")
                .path("properties").path("questions").path("items").path("properties");
        assertThat(properties.path("options").path("minItems").asInt()).isEqualTo(numberOfOptions);
        assertThat(properties.path("options").path("maxItems").asInt()).isEqualTo(numberOfOptions);
        assertThat(properties.path("answer").path("maximum").asInt()).isEqualTo(numberOfOptions - 1);
        assertThat(payload.path("messages").path(1).path("content").asText())
                .contains("esattamente " + numberOfOptions + " risposte");

        val question = new QuestionDto();
        question.setOptions(new ArrayList<>(List.of("Marte", "Venere", "Giove", "Mercurio").subList(0, numberOfOptions)));
        question.setAnswer(0);
        service.randomizeAnswerPositions(QuizDto.Request.builder().questions(List.of(question)).build(), new Random(1));
        assertThat(question.getOptions()).hasSize(numberOfOptions);
        assertThat(question.getOptions().get(question.getAnswer())).isEqualTo("Marte");
    }

    @Test
    void trueFalseSchemaUsesTwoOptionsAndNeverShufflesThem() {
        val mapper = new ObjectMapper();
        val service = createService(mapper, null, null);
        val request = generationRequest();
        request.setQuestionType(QuestionType.TRUE_FALSE);
        val payload = mapper.valueToTree(service.buildPayload(request, null));
        val properties = payload.path("response_format").path("json_schema").path("schema")
                .path("properties").path("questions").path("items").path("properties");
        assertThat(properties.path("type").path("enum").path(0).asText()).isEqualTo("TRUE_FALSE");
        assertThat(properties.path("options").path("maxItems").asInt()).isEqualTo(2);
        assertThat(properties.path("answer").path("maximum").asInt()).isEqualTo(1);
        assertThat(payload.path("messages").path(1).path("content").asText()).contains("un'affermazione", "Vero", "Falso");

        val question = new QuestionDto();
        question.setType(QuestionType.TRUE_FALSE);
        question.setOptions(new ArrayList<>(List.of("Vero", "Falso")));
        question.setAnswer(1);
        service.randomizeAnswerPositions(QuizDto.Request.builder().questions(List.of(question)).build(), new Random(1));
        assertThat(question.getOptions()).containsExactly("Vero", "Falso");
        assertThat(question.getAnswer()).isEqualTo(1);

        request.setQuestionType(QuestionType.MULTIPLE_CHOICE);
        request.setNumberOfOptions(3);
        val nextPayload = mapper.valueToTree(service.buildPayload(request, null));
        assertThat(nextPayload.path("response_format").path("json_schema").path("schema")
                .path("properties").path("questions").path("items").path("properties")
                .path("options").path("maxItems").asInt()).isEqualTo(3);
    }

    @Test
    void shouldCancelTimedOutWorkWithoutLateChangesToQuiz() throws Exception {
        val imageSearch = mock(WikimediaImageSearchService.class);
        val release = new CountDownLatch(1);
        val finished = new CountDownLatch(1);
        val interrupted = new CountDownLatch(1);
        when(imageSearch.searchImage(any(), any())).thenAnswer(invocation -> {
            try {
                // Simulate an HTTP/native operation that cannot stop immediately.
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                    }
                }
                return "https://upload.wikimedia.org/late.jpg";
            } finally {
                finished.countDown();
            }
        });
        val service = createService(null, null, imageSearch);
        ReflectionTestUtils.setField(service, "imageSearchTimeout", Duration.ofMillis(300));
        val quiz = imageQuiz(5);
        try {
            val startedAt = System.nanoTime();
            service.checkGeneratedImageUrls(quiz, true, null);
            assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(3));
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(executor(service).getQueue()).isEmpty();
            assertThat(quiz.getQuestions()).allSatisfy(question -> assertThat(question.getImageUrl()).isEmpty());
        } finally {
            release.countDown();
        }
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        service.shutdownWikimediaSearchExecutor();
        assertThat(executor(service).awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(quiz.getQuestions()).allSatisfy(question -> assertThat(question.getImageUrl()).isEmpty());
    }

    @Test
    void shouldSkipImagesImmediatelyWhenSharedQueueIsFull() throws Exception {
        val imageSearch = mock(WikimediaImageSearchService.class);
        val service = createService(null, null, imageSearch);
        val executor = executor(service);
        val started = new CountDownLatch(3);
        val release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 3; i++) {
                executor.submit(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 60; i++) executor.submit(() -> {});
            val quiz = imageQuiz(3);
            service.checkGeneratedImageUrls(quiz, true, null);

            assertThat(executor.getQueue()).hasSize(60);
            assertThat(executor.getLargestPoolSize()).isEqualTo(3);
            assertThat(quiz.getQuestions()).allSatisfy(question -> assertThat(question.getImageUrl()).isEmpty());
            verifyNoInteractions(imageSearch);
        } finally {
            release.countDown();
        }
    }

    @Test
    void shouldShareThreeWorkersAcrossConcurrentQuizRequests() throws Exception {
        val imageSearch = mock(WikimediaImageSearchService.class);
        val started = new CountDownLatch(3);
        val release = new CountDownLatch(1);
        val active = new AtomicInteger();
        val peak = new AtomicInteger();
        when(imageSearch.searchImage(any(), any())).thenAnswer(invocation -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.countDown();
            try {
                release.await();
                return "https://upload.wikimedia.org/example.jpg";
            } finally {
                active.decrementAndGet();
            }
        });
        val service = createService(null, null, imageSearch);
        val callers = Executors.newFixedThreadPool(6);
        try {
            val quizzes = new ArrayList<QuizDto.Request>();
            val requests = new ArrayList<Future<?>>();
            for (int i = 0; i < 6; i++) {
                val quiz = imageQuiz(3);
                quizzes.add(quiz);
                requests.add(callers.submit(() -> service.checkGeneratedImageUrls(quiz, true, null)));
            }
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (val request : requests) request.get(5, TimeUnit.SECONDS);

            assertThat(peak.get()).isEqualTo(3);
            assertThat(executor(service).getQueue()).isEmpty();
            assertThat(quizzes).allSatisfy(quiz -> assertThat(quiz.getQuestions())
                    .allSatisfy(question -> assertThat(question.getImageUrl())
                            .isEqualTo("https://upload.wikimedia.org/example.jpg")));
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void shouldCancelQueuedTasksAndPreserveInterruptStatus() {
        val imageSearch = mock(WikimediaImageSearchService.class);
        val service = createService(null, null, imageSearch);
        Thread.currentThread().interrupt();
        try {
            service.checkGeneratedImageUrls(imageQuiz(5), true, null);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(executor(service).getQueue()).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldAvoidExecutorWhenImagesAreDisabled() {
        val imageSearch = mock(WikimediaImageSearchService.class);
        val service = createService(null, null, imageSearch);
        service.shutdownWikimediaSearchExecutor();
        val quiz = imageQuiz(2);
        quiz.getQuestions().forEach(question -> question.setImageUrl("untrusted-url"));

        assertThatCode(() -> service.checkGeneratedImageUrls(quiz, false, null)).doesNotThrowAnyException();
        assertThat(quiz.getQuestions()).allSatisfy(question -> {
            assertThat(question.getImageUrl()).isEmpty();
            assertThat(question.getImageKeywords()).isEmpty();
        });
        verifyNoInteractions(imageSearch);
    }

    private ThreadPoolExecutor executor(OpenAiQuizGenerationService service) {
        return (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "wikimediaSearchExecutor");
    }

    private QuizDto.Request imageQuiz(int questionCount) {
        val questions = new ArrayList<QuestionDto>();
        for (int i = 0; i < questionCount; i++) {
            val question = new QuestionDto();
            question.setImageKeywords("mars, planet");
            questions.add(question);
        }
        return QuizDto.Request.builder().questions(questions).build();
    }

    private QuizGenerationRequestDto generationRequest() {
        val request = new QuizGenerationRequestDto();
        request.setTopic("Spazio");
        request.setNumberOfQuestions(3);
        request.setDifficulty("Medio");
        request.setTone("Neutro");
        return request;
    }

}
