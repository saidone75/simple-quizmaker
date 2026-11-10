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

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.ZipFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
@Slf4j
public class DocumentTextExtractorService {

    @Value("${app.ai.generation.max-attachment-chars:60000}")
    private int maxAttachmentChars = 60000;

    @Value("${app.ai.generation.max-pdf-pages:2000}")
    private int maxPdfPages = 2000;

    @Value("${app.ai.generation.max-docx-xml-bytes:67108864}")
    private long maxDocxXmlBytes = 64L * 1024 * 1024;

    public String extractText(MultipartFile file) {
        if (file == null || file.isEmpty()) return "";
        val filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        val limit = maxAttachmentChars > 0 ? maxAttachmentChars : 30000;
        try {
            if (!filename.endsWith(".pdf") && !filename.endsWith(".docx")) {
                try (val reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
                    val text = new StringBuilder();
                    val buffer = new char[4096];
                    while (text.length() < limit) {
                        val count = reader.read(buffer, 0, Math.min(buffer.length, limit - text.length()));
                        if (count < 0) break;
                        text.append(buffer, 0, count);
                    }
                    return text.toString();
                }
            }
            // Apply owner-only permissions atomically when creating uploads in the shared temp directory.
            val temporaryFile = Files.createTempFile("quizmaker-attachment-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try {
                try (val input = file.getInputStream();
                     val output = Files.newOutputStream(temporaryFile, StandardOpenOption.WRITE,
                             StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                    input.transferTo(output);
                }
                return filename.endsWith(".pdf") ? extractFromPdf(temporaryFile, limit) : extractFromDocx(temporaryFile, limit);
            } finally {
                Files.deleteIfExists(temporaryFile);
            }
        } catch (IOException e) {
            log.error("Errore durante l'estrazione del testo da: {}", filename, e);
            throw new IllegalArgumentException("Impossibile leggere il file. Usa un PDF, DOCX o testo semplice.", e);
        }
    }

    private String extractFromPdf(Path file, int limit) throws IOException {
        try (val document = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
            val output = new LimitedTextWriter(limit);
            val stripper = new PDFTextStripper() {
                private int positions;

                @Override
                protected void startPage(PDPage page) throws IOException {
                    positions = 0;
                    super.startPage(page);
                }

                @Override
                protected void processTextPosition(TextPosition position) {
                    // PDFTextStripper buffers glyphs before writing a page. Bound
                    // that buffer too, even for a single unusually dense page.
                    if (positions++ < limit) super.processTextPosition(position);
                }
            };
            stripper.setEndPage(Math.max(1, maxPdfPages));
            try {
                stripper.writeText(document, output);
            } catch (TextLimitReachedException e) {
                // The prefix is all that the generation request will use.
            }
            return output.toString();
        }
    }

    private String extractFromDocx(Path file, int limit) throws IOException {
        val output = new LimitedTextWriter(limit);
        val remainingBytes = new long[]{Math.max(1, maxDocxXmlBytes)};
        try (val zip = new ZipFile(file.toFile())) {
            if (zip.getEntry("word/document.xml") == null) throw new IOException("Documento DOCX non valido");
            val entries = zip.stream().filter(entry -> entry.getName().matches(
                    "word/(document|header[0-9]*|footer[0-9]*|footnotes|endnotes)\\.xml")).toList();
            val factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            val parser = factory.newSAXParser().getXMLReader();
            parser.setContentHandler(new DefaultHandler() {
                private boolean inText;

                @Override
                public void startElement(String uri, String localName, String name, Attributes attributes) {
                    if (isWordNamespace(uri)) {
                        inText = "t".equals(localName);
                    }
                }

                @Override
                public void characters(char[] chars, int start, int length) throws SAXException {
                    if (inText) append(chars, start, length);
                }

                @Override
                public void endElement(String uri, String localName, String name) throws SAXException {
                    if (!isWordNamespace(uri)) return;
                    if ("t".equals(localName)) inText = false;
                    if ("p".equals(localName) || "tr".equals(localName) || "br".equals(localName)) {
                        append(new char[]{'\n'}, 0, 1);
                    } else if ("tc".equals(localName) || "tab".equals(localName)) {
                        append(new char[]{'\t'}, 0, 1);
                    }
                }

                private void append(char[] chars, int start, int length) throws SAXException {
                    try {
                        output.write(chars, start, length);
                    } catch (TextLimitReachedException e) {
                        throw new SAXException(e);
                    }
                }
            });
            for (val entry : entries) {
                try (val input = new FilterInputStream(zip.getInputStream(entry)) {
                    @Override
                    public int read(byte[] bytes, int offset, int length) throws IOException {
                        val count = super.read(bytes, offset, (int) Math.min(length, Math.max(1, remainingBytes[0])));
                        if (count > 0) {
                            remainingBytes[0] -= count;
                            if (remainingBytes[0] < 0) throw new IOException("Limite XML DOCX decompresso superato");
                        }
                        return count;
                    }

                    @Override
                    public int read() throws IOException {
                        val value = super.read();
                        if (value >= 0 && --remainingBytes[0] < 0) throw new IOException("Limite XML DOCX decompresso superato");
                        return value;
                    }
                }) {
                    parser.parse(new InputSource(input));
                }
            }
        } catch (SAXException e) {
            if (!(e.getCause() instanceof TextLimitReachedException)) throw new IOException("XML DOCX non valido", e);
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new IOException("Parser DOCX non disponibile", e);
        }
        return output.toString();
    }

    private static boolean isWordNamespace(String uri) {
        return "http://schemas.openxmlformats.org/wordprocessingml/2006/main".equals(uri)
                || "http://purl.oclc.org/ooxml/wordprocessingml/main".equals(uri);
    }

    private static class TextLimitReachedException extends IOException { }

    private static class LimitedTextWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;

        private LimitedTextWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] chars, int offset, int length) throws TextLimitReachedException {
            val count = Math.min(length, limit - text.length());
            text.append(chars, offset, count);
            if (text.length() >= limit) throw new TextLimitReachedException();
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
