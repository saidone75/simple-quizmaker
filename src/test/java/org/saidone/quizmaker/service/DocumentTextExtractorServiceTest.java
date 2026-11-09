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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextExtractorServiceTest {
    private final DocumentTextExtractorService service = new DocumentTextExtractorService();

    @Test
    void extractsUtf8TextWithoutMaterializingWholeUpload() {
        ReflectionTestUtils.setField(service, "maxAttachmentChars", 8);
        val file = streamingFile("notes.txt", "Città 🌍 e altro testo".getBytes(StandardCharsets.UTF_8));
        assertThat(service.extractText(file)).isEqualTo("Città 🌍");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../alice.docx", "/tmp/alice.docx", "..\\..\\alice.docx"})
    void ignoresUploadPathsWhenCreatingTemporaryFiles(String filename) throws Exception {
        val text = "Down, down, down.";
        val file = streamingFile(filename, docx(String.format("<w:p><w:r><w:t>%s</w:t></w:r></w:p>", text)));

        assertThat(service.extractText(file)).contains(text);
    }

    @Test
    void handlesAbsentAndEmptyAttachments() {
        assertThat(service.extractText(null)).isEmpty();
        assertThat(service.extractText(streamingFile("notes.txt", new byte[0]))).isEmpty();
    }

    @Test
    void extractsPdfPrefixAndStopsAtCharacterLimit() throws Exception {
        ReflectionTestUtils.setField(service, "maxAttachmentChars", 8);
        assertThat(service.extractText(streamingFile("notes.pdf", pdf("First page", "Second page"))))
                .isEqualTo("First pa");
    }

    @Test
    void honorsConfigurablePdfPageLimit() throws Exception {
        ReflectionTestUtils.setField(service, "maxPdfPages", 1);
        assertThat(service.extractText(streamingFile("notes.pdf", pdf("First page", "Second page"))))
                .contains("First page").doesNotContain("Second page");
    }

    @Test
    void streamsDocxParagraphsAndTableText() throws Exception {
        val xml = """
                <w:p><w:r><w:t>Hello</w:t></w:r></w:p>
                <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                """;
        assertThat(service.extractText(streamingFile("notes.docx", docx(xml))))
                .contains("Hello\n", "Cell\n\t");
    }

    @Test
    void stopsDecompressingDocxWhenEnoughTextHasBeenRead() throws Exception {
        ReflectionTestUtils.setField(service, "maxAttachmentChars", 10);
        ReflectionTestUtils.setField(service, "maxDocxXmlBytes", 100_000L);
        val content = String.format("<w:p><w:r><w:t>%s</w:t></w:r></w:p>", "A".repeat(2_000_000));
        assertThat(service.extractText(streamingFile("notes.docx", docx(content)))).isEqualTo("A".repeat(10));
    }

    @Test
    void rejectsExcessiveDecompressedXmlWithoutText() throws Exception {
        ReflectionTestUtils.setField(service, "maxDocxXmlBytes", 1024L);
        val file = streamingFile("notes.docx", docx(String.format("<!--%s-->", "A".repeat(100_000))));
        assertThatThrownBy(() -> service.extractText(file)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDocxExternalEntities() throws Exception {
        val xml = String.format("""
                <?xml version="1.0"?><!DOCTYPE root [<!ENTITY leak SYSTEM "file:///etc/hostname">]>
                %s
                """, document("<w:p><w:r><w:t>&leak;</w:t></w:r></w:p>"));
        val file = streamingFile("notes.docx", zippedDocument(xml));
        assertThatThrownBy(() -> service.extractText(file)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cleansTemporaryFilesAfterSuccessAndFailureAndHandlesConcurrentExtraction() throws Exception {
        val before = temporaryFiles();
        val bytes = docx("<w:p><w:r><w:t>Concurrent text</w:t></w:r></w:p>");
        try (val executor = Executors.newFixedThreadPool(4)) {
            val requests = java.util.stream.IntStream.range(0, 12)
                    .mapToObj(i -> (Callable<String>) () -> service.extractText(streamingFile("notes.docx", bytes)))
                    .toList();
            for (val result : executor.invokeAll(requests)) assertThat(result.get()).contains("Concurrent text");
        }
        assertThatThrownBy(() -> service.extractText(streamingFile("broken.pdf", new byte[]{1, 2, 3})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.extractText(streamingFile("broken.docx", new byte[]{1, 2, 3})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(temporaryFiles()).containsExactlyInAnyOrderElementsOf(before);
    }

    private List<Path> temporaryFiles() throws Exception {
        try (val files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("quizmaker-attachment-")).toList();
        }
    }

    private MockMultipartFile streamingFile(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes) {
            @Override
            public byte[] getBytes() {
                throw new AssertionError("Extraction must use streams, not getBytes()");
            }
        };
    }

    private byte[] pdf(String... pages) throws Exception {
        try (val document = new PDDocument(); val output = new ByteArrayOutputStream()) {
            for (val text : pages) {
                val page = new PDPage();
                document.addPage(page);
                try (val content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] docx(String body) throws Exception {
        return zippedDocument(document(body));
    }

    private String document(String body) {
        return String.format("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:body>%s</w:body>
                </w:document>
                """, body);
    }

    private byte[] zippedDocument(String xml) throws Exception {
        val output = new ByteArrayOutputStream();
        try (val zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
