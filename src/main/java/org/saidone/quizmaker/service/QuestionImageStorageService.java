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

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.apache.tika.Tika;
import org.saidone.quizmaker.dto.QuestionImageUploadDto;
import org.saidone.quizmaker.entity.UploadedImage;
import org.saidone.quizmaker.entity.Teacher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.saidone.quizmaker.repository.UploadedImageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuestionImageStorageService {

    private static final Tika TIKA_DETECTOR = new Tika();

    private final UploadedImageRepository uploadedImageRepository;

    @Value("${app.upload.directory:./upload}")
    private String uploadDirectory;

    @Transactional
    @PreAuthorize("@teacherAuthorizationPolicy.isTeacher(#teacher)")
    public QuestionImageUploadDto store(MultipartFile file, Teacher teacher) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Seleziona un file immagine da caricare.");
        }
        val extension = detectImageExtension(file);
        if (extension == null) {
            throw new IllegalArgumentException("Il file selezionato non è un'immagine valida.");
        }

        try {
            val baseDir = Path.of(uploadDirectory).toAbsolutePath().normalize();
            Files.createDirectories(baseDir);

            val imageId = UUID.randomUUID();
            val fileName = String.format("%s%s", imageId, extension);
            val destination = baseDir.resolve(fileName).normalize();

            if (!destination.startsWith(baseDir)) {
                throw new IllegalStateException("Percorso di destinazione non valido.");
            }

            try (val inputStream = file.getInputStream();
                 val outputStream = Files.newOutputStream(destination, StandardOpenOption.WRITE,
                         StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)) {
                inputStream.transferTo(outputStream);
            }

            val image = new UploadedImage();
            image.setId(imageId);
            image.setTeacherId(teacher.getId());
            image.setFilePath(destination.toString());
            uploadedImageRepository.save(image);

            return QuestionImageUploadDto.builder()
                    .id(imageId)
                    .url(imageUrl(imageId))
                    .build();
        } catch (IOException exception) {
            throw new IllegalStateException("Errore durante il salvataggio del file immagine.", exception);
        }
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@questionImageAuthorizationPolicy.canRead(#imageId)")
    public Resource load(UUID imageId) {
        val image = uploadedImageRepository.findById(imageId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("Immagine non trovata: %s", imageId)));

        try {
            val resource = new UrlResource(Path.of(image.getFilePath()).toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new EntityNotFoundException(String.format("File immagine non disponibile: %s", imageId));
            }
            return resource;
        } catch (MalformedURLException exception) {
            throw new IllegalStateException("Percorso file immagine non valido.", exception);
        }
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@questionImageAuthorizationPolicy.canRead(#imageId)")
    public MediaType resolveMediaType(UUID imageId) {
        val image = uploadedImageRepository.findById(imageId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("Immagine non trovata: %s", imageId)));

        try {
            val contentType = Files.probeContentType(Path.of(image.getFilePath()));
            if (contentType == null || contentType.isBlank()) {
                return MediaType.APPLICATION_OCTET_STREAM;
            }
            return MediaType.parseMediaType(contentType);
        } catch (IOException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN') && @questionImageAuthorizationPolicy.canRead(#imageId)")
    public QuestionImageUploadDto duplicateForTeacher(UUID imageId, Teacher recipient) {
        val source = uploadedImageRepository.findById(imageId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("Immagine non trovata: %s", imageId)));
        val copy = new UploadedImage();
        copy.setId(UUID.randomUUID());
        copy.setTeacherId(recipient.getId());
        copy.setFilePath(source.getFilePath());
        uploadedImageRepository.save(copy);
        return QuestionImageUploadDto.builder().id(copy.getId()).url(imageUrl(copy.getId())).build();
    }

    @Transactional
    @PreAuthorize("@questionImageAuthorizationPolicy.canDelete(#imageId, #teacher)")
    public void deleteForTeacher(UUID imageId, Teacher teacher) {
        delete(imageId);
    }

    // Internal cleanup entry point; never expose directly through a controller.
    @Transactional
    public void delete(UUID imageId) {
        val image = uploadedImageRepository.findById(imageId)
                .orElseThrow(() -> new EntityNotFoundException(String.format("Immagine non trovata: %s", imageId)));

        uploadedImageRepository.delete(image);
        uploadedImageRepository.flush();
        // Only remove the binary after the DB deletion commits, and only when
        // neither the original record nor any shared copy still references it.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteUnreferencedBinary(image.getFilePath());
                }
            });
        } else {
            deleteUnreferencedBinary(image.getFilePath());
        }
    }

    private void deleteUnreferencedBinary(String filePath) {
        if (uploadedImageRepository.existsByFilePath(filePath)) return;
        try {
            Files.deleteIfExists(Path.of(filePath));
        } catch (IOException exception) {
            log.error("Errore durante la rimozione del file immagine non più referenziato: {}", filePath, exception);
        }
    }

    public String imageUrl(UUID imageId) {
        return String.format("/api/quizzes/images/%s", imageId);
    }

    private boolean hasAllowedTypeHint(MultipartFile file) {
        val contentType = file.getContentType();
        if (contentType != null && isAllowedContentType(contentType)) {
            return true;
        }

        val extension = extractExtension(file.getOriginalFilename());
        return extension.equals(".png")
                || extension.equals(".jpg")
                || extension.equals(".jpeg")
                || extension.equals(".gif")
                || extension.equals(".webp")
                || extension.equals(".bmp");
    }

    private boolean isAllowedContentType(String contentType) {
        val normalizedContentType = contentType.toLowerCase(Locale.ROOT).trim();
        return normalizedContentType.equals("image/png")
                || normalizedContentType.equals("image/jpeg")
                || normalizedContentType.equals("image/gif")
                || normalizedContentType.equals("image/webp")
                || normalizedContentType.equals("image/bmp")
                || normalizedContentType.equals("image/x-ms-bmp");
    }

    private String detectImageExtension(MultipartFile file) {
        if (!hasAllowedTypeHint(file)) return null;
        try (val inputStream = file.getInputStream()) {
            val signature = inputStream.readNBytes(16384);
            // Only trusted literals derived from the signature reach the path.
            return switch (TIKA_DETECTOR.detect(signature)) {
                case "image/png" -> ".png";
                case "image/jpeg" -> ".jpg";
                case "image/gif" -> ".gif";
                case "image/webp" -> ".webp";
                case "image/bmp", "image/x-ms-bmp" -> ".bmp";
                default -> null;
            };
        } catch (IOException ignored) {
            return null;
        }
    }

    private String extractExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        val dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
    }
}
