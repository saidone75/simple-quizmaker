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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.saidone.quizmaker.entity.Question;
import org.saidone.quizmaker.entity.Quiz;
import org.saidone.quizmaker.entity.UploadedImage;
import org.saidone.quizmaker.repository.QuizRepository;
import org.saidone.quizmaker.repository.UploadedImageRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.image-cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrphanQuestionImageCleanupScheduler {

    private static final int BATCH_SIZE = 100;

    private final QuizRepository quizRepository;
    private final UploadedImageRepository uploadedImageRepository;
    private final QuestionImageStorageService questionImageStorageService;

    @Scheduled(cron = "${app.image-cleanup.cron:0 0 3 * * *}")
    public void cleanupOrphanImages() {
        try {
            val cutoff = LocalDateTime.now(ZoneId.systemDefault()).minusDays(1);
            val usedImageIds = findUsedImageIds();
            val deletedCount = deleteOrphanImages(usedImageIds, cutoff);

            log.info("Pulizia immagini orfane completata. Immagini referenziate: {}, immagini eliminate: {}",
                    usedImageIds.size(), deletedCount);
        } catch (Exception ex) {
            log.error("Pulizia immagini orfane fallita.", ex);
        }
    }

    private Set<UUID> findUsedImageIds() {
        val usedImageIds = new HashSet<UUID>();
        // Keep only referenced UUIDs between batches, never all quiz payloads.
        // Each repository call has its own persistence context: no outer transaction.
        var quizPage = PageRequest.of(0, BATCH_SIZE);
        while (true) {
            val quizzes = quizRepository.findAllByOrderByIdAsc(quizPage);
            quizzes.forEach(quiz -> collectQuizImageIds(quiz, usedImageIds));
            if (!quizzes.hasNext()) break;
            quizPage = quizPage.next();
        }
        return usedImageIds;
    }

    private void collectQuizImageIds(Quiz quiz, Set<UUID> usedImageIds) {
        if (quiz.getQuestions() == null) return;
        for (val question : quiz.getQuestions()) {
            if (question != null) collectQuestionImageIds(question, usedImageIds);
        }
    }

    private void collectQuestionImageIds(Question question, Set<UUID> usedImageIds) {
        if (question.getImageId() != null && !question.getImageId().isBlank()) {
            parseUuid(question.getImageId()).ifPresent(usedImageIds::add);
        }
        imageIdFromUrl(question.getImageUrl()).ifPresent(usedImageIds::add);
    }

    private Optional<UUID> imageIdFromUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) return Optional.empty();
        try {
            val path = URI.create(imageUrl.trim()).getPath();
            val prefix = "/api/quizzes/images/";
            if (path != null && path.startsWith(prefix)) {
                return parseUuid(path.substring(prefix.length()));
            }
        } catch (IllegalArgumentException _) {
            log.debug("URL immagine non valido ignorato durante la pulizia immagini orfane");
        }
        return Optional.empty();
    }

    private int deleteOrphanImages(Set<UUID> usedImageIds, LocalDateTime cutoff) {
        var deletedCount = 0;
        val imagePage = PageRequest.of(0, BATCH_SIZE);
        var images = uploadedImageRepository.findAllByOrderByIdAsc(imagePage);
        while (images.hasContent()) {
            // Advance by ID, not offset: deleting a batch must not skip rows.
            val lastImageId = images.getContent().getLast().getId();
            for (val image : images) {
                if (isOrphanBeforeCutoff(image, usedImageIds, cutoff)) {
                    questionImageStorageService.delete(image.getId());
                    deletedCount++;
                }
            }
            if (!images.hasNext()) break;
            images = uploadedImageRepository.findByIdGreaterThanOrderByIdAsc(lastImageId, imagePage);
        }
        return deletedCount;
    }

    private boolean isOrphanBeforeCutoff(UploadedImage image, Set<UUID> usedImageIds, LocalDateTime cutoff) {
        return !usedImageIds.contains(image.getId()) && image.getCreatedAt() != null
                && image.getCreatedAt().isBefore(cutoff);
    }

    private Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException _) {
            log.debug("Image ID non valido ignorato durante la pulizia immagini orfane: {}", value);
            return Optional.empty();
        }
    }
}
