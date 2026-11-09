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
import org.saidone.quizmaker.repository.QuizRepository;
import org.saidone.quizmaker.repository.UploadedImageRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashSet;
import java.util.Optional;
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
            val usedImageIds = new HashSet<UUID>();

            // Keep only referenced UUIDs between batches, never all quiz payloads.
            // Each repository call has its own persistence context: no outer transaction.
            var quizPage = PageRequest.of(0, BATCH_SIZE);
            while (true) {
                val quizzes = quizRepository.findAllByOrderByIdAsc(quizPage);
                quizzes.forEach(quiz -> {
                    if (quiz.getQuestions() == null) {
                        return;
                    }
                    for (val question : quiz.getQuestions()) {
                        if (question == null) continue;
                        if (question.getImageId() != null && !question.getImageId().isBlank()) {
                            parseUuid(question.getImageId()).ifPresent(usedImageIds::add);
                        }
                        if (question.getImageUrl() != null && !question.getImageUrl().isBlank()) {
                            try {
                                val path = URI.create(question.getImageUrl().trim()).getPath();
                                val prefix = "/api/quizzes/images/";
                                if (path != null && path.startsWith(prefix)) {
                                    parseUuid(path.substring(prefix.length())).ifPresent(usedImageIds::add);
                                }
                            } catch (IllegalArgumentException ex) {
                                log.debug("URL immagine non valido ignorato durante la pulizia immagini orfane");
                            }
                        }
                    }
                });
                if (!quizzes.hasNext()) break;
                quizPage = quizPage.next();
            }

            var deletedCount = 0;
            val imagePage = PageRequest.of(0, BATCH_SIZE);
            var images = uploadedImageRepository.findAllByOrderByIdAsc(imagePage);
            while (images.hasContent()) {
                // Advance by ID, not offset: deleting a batch must not skip rows.
                val lastImageId = images.getContent().getLast().getId();
                for (val image : images) {
                    if (usedImageIds.contains(image.getId())) continue;
                    questionImageStorageService.delete(image.getId());
                    deletedCount++;
                }
                if (!images.hasNext()) break;
                images = uploadedImageRepository.findByIdGreaterThanOrderByIdAsc(lastImageId, imagePage);
            }

            log.info("Pulizia immagini orfane completata. Immagini referenziate: {}, immagini eliminate: {}",
                    usedImageIds.size(), deletedCount);
        } catch (Exception ex) {
            log.error("Pulizia immagini orfane fallita.", ex);
        }
    }

    private Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value.trim()));
        } catch (IllegalArgumentException ex) {
            log.debug("Image ID non valido ignorato durante la pulizia immagini orfane: {}", value);
            return Optional.empty();
        }
    }
}
