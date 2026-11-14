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
import org.springframework.transaction.support.TransactionSynchronization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.saidone.quizmaker.repository.UploadedImageRepository;
import org.saidone.quizmaker.entity.Teacher;
import org.saidone.quizmaker.entity.UploadedImage;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Optional;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestionImageStorageServiceTest {

    @Mock
    private UploadedImageRepository uploadedImageRepository;

    @TempDir
    Path tempDir;

    private QuestionImageStorageService service;
    private final Teacher teacher = Teacher.builder().id(UUID.randomUUID()).build();

    @BeforeEach
    void setUp() {
        service = new QuestionImageStorageService(uploadedImageRepository);
        ReflectionTestUtils.setField(service, "uploadDirectory", tempDir.toString());
    }

    @Test
    void store_rejectsSvgUpload() {
        val file = new MockMultipartFile(
                "file",
                "dangerous.svg",
                "image/svg+xml",
                "<svg><script>alert('xss')</script></svg>".getBytes()
        );

        assertThatThrownBy(() -> service.store(file, teacher))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Il file selezionato non è un'immagine valida.");
        verify(uploadedImageRepository, never()).save(any());
    }

    @Test
    void store_acceptsPngUpload() {
        val file = new MockMultipartFile(
                "file",
                "safe.png",
                "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3}
        );
        when(uploadedImageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        val result = service.store(file, teacher);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getUrl()).isEqualTo(String.format("/api/quizzes/images/%s", result.getId()));
        val imageCaptor = ArgumentCaptor.forClass(UploadedImage.class);
        verify(uploadedImageRepository).save(imageCaptor.capture());
        assertThat(imageCaptor.getValue().getTeacherId()).isEqualTo(teacher.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../alice.png", "/tmp/alice.png", "..\\..\\alice.png", "alice.jpg", "alice.png/../../outside"})
    void store_usesSignatureAndGeneratedIdForPath(String filename) throws Exception {
        val png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        val file = new MockMultipartFile("file", filename, "image/png", png);

        val result = service.store(file, teacher);

        val imageCaptor = ArgumentCaptor.forClass(UploadedImage.class);
        verify(uploadedImageRepository).save(imageCaptor.capture());
        val destination = Path.of(imageCaptor.getValue().getFilePath());
        assertThat(destination.getParent()).isEqualTo(tempDir.toAbsolutePath().normalize());
        assertThat(destination.getFileName().toString()).isEqualTo(String.format("%s.png", result.getId()));
        assertThat(Files.readAllBytes(destination)).isEqualTo(png);
    }

    @Test
    void store_closesUploadStreamAfterSuccessfulCopy() throws Exception {
        val png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        val file = spy(new MockMultipartFile("file", "safe.png", "image/png", png));
        val validationStream = spy(new ByteArrayInputStream(png));
        val uploadStream = spy(new ByteArrayInputStream(png));
        when(file.getInputStream()).thenReturn(validationStream, uploadStream);

        val result = service.store(file, teacher);

        verify(validationStream).close();
        verify(uploadStream).close();
        val imageCaptor = ArgumentCaptor.forClass(UploadedImage.class);
        verify(uploadedImageRepository).save(imageCaptor.capture());
        assertThat(Files.readAllBytes(Path.of(imageCaptor.getValue().getFilePath()))).isEqualTo(png);
        assertThat(result.getId()).isEqualTo(imageCaptor.getValue().getId());
    }

    @Test
    void store_closesUploadStreamWhenCopyFails() throws Exception {
        val png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        val file = spy(new MockMultipartFile("file", "safe.png", "image/png", png));
        val validationStream = spy(new ByteArrayInputStream(png));
        val failure = new IOException("Copy failed");
        val uploadStream = spy(new InputStream() {
            @Override
            public int read() throws IOException {
                throw failure;
            }
        });
        when(file.getInputStream()).thenReturn(validationStream, uploadStream);

        assertThatThrownBy(() -> service.store(file, teacher))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(failure);

        verify(validationStream).close();
        verify(uploadStream).close();
        verify(uploadedImageRepository, never()).save(any());
    }

    @Test
    void store_rejectsSpoofedPngWithSvgPayload() {
        val file = new MockMultipartFile(
                "file",
                "fake.png",
                "image/png",
                "<svg><script>alert('xss')</script></svg>".getBytes()
        );

        assertThatThrownBy(() -> service.store(file, teacher))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Il file selezionato non è un'immagine valida.");
        verify(uploadedImageRepository, never()).save(any());
    }

    @Test
    void duplicateCreatesOwnedRecordWithoutCopyingBinary() throws Exception {
        val source = savedImage();
        val recipient = Teacher.builder().id(UUID.randomUUID()).build();
        val result = service.duplicateForTeacher(source.getId(), recipient);

        val captor = ArgumentCaptor.forClass(UploadedImage.class);
        verify(uploadedImageRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNotEqualTo(source.getId()).isEqualTo(result.getId());
        assertThat(captor.getValue().getTeacherId()).isEqualTo(recipient.getId());
        assertThat(captor.getValue().getFilePath()).isEqualTo(source.getFilePath());
        try (val files = Files.list(tempDir)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void deletingRecordPreservesBinaryUsedByAnotherRecord() throws Exception {
        val source = savedImage();
        when(uploadedImageRepository.existsByFilePath(source.getFilePath())).thenReturn(true);
        service.delete(source.getId());
        verify(uploadedImageRepository).delete(source);
        assertThat(Path.of(source.getFilePath())).exists();
    }

    @Test
    void sharedCopyRemainsReadableAfterOriginalIsDeletedAndLastDeletionRemovesFile() throws Exception {
        val source = imageOnDisk();
        Map<UUID, UploadedImage> records = new HashMap<>();
        records.put(source.getId(), source);
        when(uploadedImageRepository.findById(any())).thenAnswer(invocation ->
                Optional.ofNullable(records.get(invocation.getArgument(0))));
        when(uploadedImageRepository.save(any())).thenAnswer(invocation -> {
            UploadedImage image = invocation.getArgument(0);
            records.put(image.getId(), image);
            return image;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            UploadedImage image = invocation.getArgument(0);
            records.remove(image.getId());
            return null;
        }).when(uploadedImageRepository).delete(any());
        when(uploadedImageRepository.existsByFilePath(any())).thenAnswer(invocation ->
                records.values().stream().anyMatch(image -> image.getFilePath().equals(invocation.getArgument(0))));

        val copy = service.duplicateForTeacher(source.getId(), Teacher.builder().id(UUID.randomUUID()).build());
        service.delete(source.getId());
        assertThat(service.load(copy.getId()).getContentAsByteArray()).containsExactly(1, 2, 3);
        service.delete(copy.getId());
        assertThat(Path.of(source.getFilePath())).doesNotExist();
        assertThat(records).isEmpty();
    }

    @Test
    void deletingLastRecordRemovesBinaryOnlyAfterCommit() throws Exception {
        val source = savedImage();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(source.getId());
            assertThat(Path.of(source.getFilePath())).exists();
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            assertThat(Path.of(source.getFilePath())).doesNotExist();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackDoesNotRemoveBinary() throws Exception {
        val source = savedImage();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.delete(source.getId());
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(1));
            assertThat(Path.of(source.getFilePath())).exists();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private UploadedImage savedImage() throws Exception {
        val source = imageOnDisk();
        when(uploadedImageRepository.findById(source.getId())).thenReturn(Optional.of(source));
        return source;
    }

    private UploadedImage imageOnDisk() throws Exception {
        val source = new UploadedImage();
        source.setId(UUID.randomUUID());
        source.setTeacherId(teacher.getId());
        source.setFilePath(Files.write(tempDir.resolve("shared.png"), new byte[]{1, 2, 3}).toString());
        return source;
    }

    @Test
    void inputFailureAfterCreatingDestinationRemovesPartialUpload() throws Exception {
        val file = spy(pngUpload());
        when(file.getInputStream()).thenCallRealMethod().thenThrow(new IOException("Read failed"));

        assertThatThrownBy(() -> service.store(file, teacher)).isInstanceOf(IllegalStateException.class);

        try (val files = Files.list(tempDir)) {
            assertThat(files.count()).isZero();
        }
        verify(uploadedImageRepository, never()).save(any());
    }

    @Test
    void failedDatabaseSaveRemovesUploadedBinary() throws Exception {
        val file = pngUpload();
        when(uploadedImageRepository.save(any())).thenThrow(new IllegalStateException("Database unavailable"));

        assertThatThrownBy(() -> service.store(file, teacher)).isInstanceOf(IllegalStateException.class);

        try (val files = Files.list(tempDir)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    void transactionRollbackRemovesUploadedBinaryButCommitPreservesIt() throws Exception {
        val file = pngUpload();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.store(file, teacher);
            try (val files = Files.list(tempDir)) {
                assertThat(files.count()).isEqualTo(1);
            }
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            try (val files = Files.list(tempDir)) {
                assertThat(files.count()).isZero();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.store(file, teacher);
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            try (val files = Files.list(tempDir)) {
                assertThat(files.count()).isEqualTo(1);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private MockMultipartFile pngUpload() {
        return new MockMultipartFile("file", "image.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3});
    }

}
