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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.val;
import lombok.extern.slf4j.Slf4j;
import org.saidone.quizmaker.config.ShortLinkProperties;
import org.saidone.quizmaker.entity.ShortLink;
import org.saidone.quizmaker.repository.ShortLinkRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Optional;

/** Reusable short links for internal application paths; no quiz or HTTP dependencies. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShortLinkService {
    private static final int MAX_ATTEMPTS = 5;
    private final ShortLinkRepository repository;
    private final PlatformTransactionManager transactionManager;
    private final ShortLinkProperties properties;
    private final SecureRandom random = new SecureRandom();
    private TransactionTemplate transactions;
    private String alphabet;
    private int length;

    @PostConstruct
    void initialize() {
        transactions = new TransactionTemplate(transactionManager);
        alphabet = properties.getAlphabet();
        length = properties.getLength();
        if (alphabet == null || !alphabet.matches("[a-zA-Z0-9]{2,62}")
                || alphabet.chars().distinct().count() != alphabet.length()) {
            throw new IllegalArgumentException("Short-link alphabet must contain 2–62 distinct ASCII letters or digits");
        }
        if (length < 8 || length > 64) {
            throw new IllegalArgumentException("Short-link length must be between 8 and 64");
        }
    }

    /** Returns the stable code for a path, creating it only on first use. */
    public String getOrCreate(String targetPath) {
        validateTargetPath(targetPath);
        // A failed insert must roll back before retrying a collision or a concurrent creation.
        // If the caller owns the transaction, it must retry that entire transaction instead.
        val callerTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return transactions.execute(_ -> repository.findByTargetPath(targetPath)
                        .map(ShortLink::getCode)
                        .orElseGet(() -> repository.saveAndFlush(new ShortLink(randomCode(), targetPath)).getCode()));
            } catch (DataIntegrityViolationException exception) {
                if (callerTransaction || attempt == MAX_ATTEMPTS - 1) throw exception;
                log.debug("Conflitto durante la creazione del link breve: nuovo tentativo {}/{}", attempt + 2, MAX_ATTEMPTS);
            }
        }
        throw new IllegalStateException("Unable to create short link");
    }

    /** Existing codes remain valid if generation rules change later. */
    public Optional<String> resolve(String code) {
        if (code == null || !code.matches("[a-zA-Z0-9]{8,64}")) return Optional.empty();
        return repository.findById(code).map(ShortLink::getTargetPath);
    }

    private String randomCode() {
        val code = new StringBuilder(length);
        for (int i = 0; i < length; i++) code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return code.toString();
    }

    private void validateTargetPath(String targetPath) {
        if (targetPath == null || targetPath.length() > 512 || !targetPath.startsWith("/")
                || targetPath.startsWith("//") || targetPath.contains("\\")) {
            throw new IllegalArgumentException("Short links require an internal absolute path");
        }
        val uri = URI.create(targetPath);
        if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null
                || targetPath.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid short-link path");
        }
    }
}
