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

import lombok.val;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.saidone.quizmaker.entity.Question;
import org.saidone.quizmaker.entity.Quiz;
import org.saidone.quizmaker.entity.UploadedImage;
import org.saidone.quizmaker.repository.QuizRepository;
import org.saidone.quizmaker.repository.UploadedImageRepository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrphanQuestionImageCleanupSchedulerTest {

    @Mock
    private QuizRepository quizRepository;

    @Mock
    private UploadedImageRepository uploadedImageRepository;

    @Mock
    private QuestionImageStorageService questionImageStorageService;

    @InjectMocks
    private OrphanQuestionImageCleanupScheduler cleanupScheduler;

    @Test
    void cleanupOrphanImages_deletesOnlyUnreferencedImages() {
        val usedImageId = UUID.randomUUID();
        val orphanImageId = UUID.randomUUID();

        val question = new Question();
        question.setImageId(usedImageId.toString());

        val quiz = Quiz.builder()
                .id(UUID.randomUUID())
                .title("Quiz")
                .emoji("🧪")
                .questions(List.of(question))
                .published(true)
                .archived(false)
                .build();

        val usedImage = new UploadedImage();
        usedImage.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(2));
        usedImage.setId(usedImageId);
        usedImage.setFilePath("/tmp/used.png");

        val orphanImage = new UploadedImage();
        orphanImage.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(2));
        orphanImage.setId(orphanImageId);
        orphanImage.setFilePath("/tmp/orphan.png");

        when(quizRepository.findAllByOrderByIdAsc(any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(quiz)));
        when(uploadedImageRepository.findAllByOrderByIdAsc(any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(usedImage, orphanImage)));

        cleanupScheduler.cleanupOrphanImages();

        verify(questionImageStorageService, times(1)).delete(orphanImageId);
        verify(questionImageStorageService, never()).delete(usedImageId);
    }

    @Test
    void cleanupOrphanImages_ignoresInvalidImageIdsAndDeletesStoredImages() {
        val question = new Question();
        question.setImageId("not-a-uuid");

        val quiz = Quiz.builder()
                .id(UUID.randomUUID())
                .title("Quiz")
                .emoji("🧪")
                .questions(List.of(question))
                .published(true)
                .archived(false)
                .build();

        val storedImage = new UploadedImage();
        storedImage.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(2));
        storedImage.setId(UUID.randomUUID());
        storedImage.setFilePath("/tmp/orphan.png");

        when(quizRepository.findAllByOrderByIdAsc(any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(quiz)));
        when(uploadedImageRepository.findAllByOrderByIdAsc(any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(storedImage)));

        cleanupScheduler.cleanupOrphanImages();

        verify(questionImageStorageService, times(1)).delete(storedImage.getId());
    }

    @Test
    void cleanupPreservesImageReferencedOnlyByLocalUrl() {
        val id = UUID.randomUUID();
        val question = new Question();
        question.setImageUrl(String.format("/api/quizzes/images/%s", id));
        val image = new UploadedImage();
        image.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(2));
        image.setId(id);
        when(quizRepository.findAllByOrderByIdAsc(any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(Quiz.builder().questions(List.of(question)).build())));
        when(uploadedImageRepository.findAllByOrderByIdAsc(any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(image)));

        cleanupScheduler.cleanupOrphanImages();

        verifyNoInteractions(questionImageStorageService);
    }

    @Test
    void cleanupScansAllQuizBatchesBeforeDeletingImagesAndUsesImageCursor() {
        val earlyImageId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        val orphanImageId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        val laterImageId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        val earlyQuestion = new Question();
        earlyQuestion.setImageId(earlyImageId.toString());
        val laterQuestion = new Question();
        laterQuestion.setImageUrl(String.format("/api/quizzes/images/%s", laterImageId));
        val firstQuiz = Quiz.builder().questions(List.of(earlyQuestion)).build();
        val archivedQuiz = Quiz.builder().questions(List.of(laterQuestion)).archived(true).build();
        val firstPage = PageRequest.of(0, 100);
        when(quizRepository.findAllByOrderByIdAsc(firstPage))
                .thenReturn(new SliceImpl<>(List.of(firstQuiz), firstPage, true));
        when(quizRepository.findAllByOrderByIdAsc(firstPage.next()))
                .thenReturn(new SliceImpl<>(List.of(archivedQuiz), firstPage.next(), false));
        val earlyImage = new UploadedImage();
        earlyImage.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(2));
        earlyImage.setId(earlyImageId);
        val orphan = new UploadedImage();
        orphan.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(2));
        orphan.setId(orphanImageId);
        val laterImage = new UploadedImage();
        laterImage.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()).minusDays(2));
        laterImage.setId(laterImageId);
        when(uploadedImageRepository.findAllByOrderByIdAsc(firstPage))
                .thenReturn(new SliceImpl<>(List.of(earlyImage, orphan), firstPage, true));
        when(uploadedImageRepository.findByIdGreaterThanOrderByIdAsc(orphanImageId, firstPage))
                .thenReturn(new SliceImpl<>(List.of(laterImage), firstPage, false));

        cleanupScheduler.cleanupOrphanImages();

        val order = inOrder(quizRepository, uploadedImageRepository, questionImageStorageService);
        order.verify(quizRepository).findAllByOrderByIdAsc(firstPage);
        order.verify(quizRepository).findAllByOrderByIdAsc(firstPage.next());
        order.verify(uploadedImageRepository).findAllByOrderByIdAsc(firstPage);
        order.verify(questionImageStorageService).delete(orphanImageId);
        order.verify(uploadedImageRepository).findByIdGreaterThanOrderByIdAsc(orphanImageId, firstPage);
        verify(questionImageStorageService, never()).delete(earlyImageId);
        verify(questionImageStorageService, never()).delete(laterImageId);
        verify(quizRepository, never()).findAll();
        verify(uploadedImageRepository, never()).findAll();
    }

    @Test
    void cleanupDoesNotDeleteImagesWhenQuizScanFails() {
        when(quizRepository.findAllByOrderByIdAsc(any(Pageable.class)))
                .thenThrow(new IllegalStateException("Database unavailable"));

        cleanupScheduler.cleanupOrphanImages();

        verifyNoInteractions(uploadedImageRepository, questionImageStorageService);
    }
    @Test
    void cleanupPreservesRecentlyUploadedImages() {
        val image = new UploadedImage();
        image.setId(UUID.randomUUID());
        image.setCreatedAt(LocalDateTime.now(ZoneId.systemDefault()));
        when(quizRepository.findAllByOrderByIdAsc(any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of()));
        when(uploadedImageRepository.findAllByOrderByIdAsc(any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(image)));

        cleanupScheduler.cleanupOrphanImages();

        verifyNoInteractions(questionImageStorageService);
    }

}
