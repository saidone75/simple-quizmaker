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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import lombok.val;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class BruteForceProtectionService {

    static final int MAX_LOGIN_FAILURES = 3;
    static final Duration LOGIN_WINDOW = Duration.ofMinutes(1);
    static final Duration LOGIN_LOCK = Duration.ofMinutes(5);
    static final int MAX_STUDENT_LOGIN_FAILURES = 5;
    static final Duration STUDENT_LOGIN_WINDOW = Duration.ofMinutes(1);
    static final Duration STUDENT_LOGIN_LOCK = Duration.ofMinutes(3);

    static final int MAX_REGISTER_ATTEMPTS = 10;
    static final Duration REGISTER_WINDOW = Duration.ofMinutes(10);

    private static final String STUDENT_IP_KEY_PREFIX = "student-ip";
    private static final String STUDENT_KEYWORD_KEY_PREFIX = "student-keyword";
    private static final String KEY_FORMAT = "%s|%s";

    static final int MAX_TRACKED_KEYS = 10_000;

    private final Cache<String, LoginState> loginStates;
    private final Cache<String, Deque<Instant>> registerAttempts;
    private final Clock clock;

    @Autowired
    public BruteForceProtectionService() {
        this(Clock.systemUTC(), Ticker.systemTicker());
    }

    BruteForceProtectionService(Clock clock) {
        this(clock, () -> TimeUnit.MILLISECONDS.toNanos(clock.millis()));
    }

    private BruteForceProtectionService(Clock clock, Ticker ticker) {
        this.clock = clock;
        // Retain entries longer than every applicable window/lock. Reads must not
        // extend retention; stale keys are reclaimed even if they are polled.
        loginStates = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_KEYS)
                .expireAfterWrite(LOGIN_LOCK.plus(LOGIN_WINDOW))
                .ticker(ticker)
                .build();
        registerAttempts = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_KEYS)
                .expireAfterWrite(REGISTER_WINDOW.plusMinutes(1))
                .ticker(ticker)
                .build();
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanupExpiredEntries() {
        loginStates.cleanUp();
        registerAttempts.cleanUp();
    }

    public boolean isLoginBlocked(String key) {
        val state = loginStates.getIfPresent(key);
        if (state == null) {
            return false;
        }
        val blockedUntil = state.blockedUntil;
        return blockedUntil != null && blockedUntil.isAfter(Instant.now(clock));
    }

    private static String key(String prefix, String suffix) {
        return String.format(KEY_FORMAT, prefix, suffix);
    }

    public void recordLoginFailure(String key) {
        recordLoginFailure(key, MAX_LOGIN_FAILURES, LOGIN_WINDOW, LOGIN_LOCK);
    }

    public void recordStudentLoginFailureByIp(String ipAddress) {
        recordLoginFailure(key(STUDENT_IP_KEY_PREFIX, ipAddress), MAX_STUDENT_LOGIN_FAILURES, STUDENT_LOGIN_WINDOW, STUDENT_LOGIN_LOCK);
    }

    public void recordStudentLoginFailureByKeyword(String keyword) {
        recordLoginFailure(key(STUDENT_KEYWORD_KEY_PREFIX, keyword.toLowerCase(Locale.ROOT)), MAX_STUDENT_LOGIN_FAILURES, STUDENT_LOGIN_WINDOW, STUDENT_LOGIN_LOCK);
    }

    public boolean isStudentLoginBlocked(String ipAddress, String keyword) {
        return isLoginBlocked(key(STUDENT_IP_KEY_PREFIX, ipAddress))
                || isLoginBlocked(key(STUDENT_KEYWORD_KEY_PREFIX, keyword.toLowerCase(Locale.ROOT)));
    }

    public void clearStudentLoginFailures(String ipAddress, String keyword) {
        clearLoginFailures(key(STUDENT_IP_KEY_PREFIX, ipAddress));
        clearLoginFailures(key(STUDENT_KEYWORD_KEY_PREFIX, keyword.toLowerCase(Locale.ROOT)));
    }

    private void recordLoginFailure(String key, int maxFailures, Duration window, Duration lockDuration) {
        val now = Instant.now(clock);
        loginStates.asMap().compute(key, (k, state) -> {
            if (state == null || (state.blockedUntil != null && !state.blockedUntil.isAfter(now))) {
                state = new LoginState();
            }

            if (state.blockedUntil != null && state.blockedUntil.isAfter(now)) {
                return state;
            }

            state.prune(now, window);
            state.failures.addLast(now);
            if (state.failures.size() >= maxFailures) {
                state.blockedUntil = now.plus(lockDuration);
            }
            return state;
        });
    }

    public void clearLoginFailures(String key) {
        loginStates.invalidate(key);
    }

    public boolean consumeRegisterAttempt(String ipKey) {
        val accepted = new AtomicBoolean();
        registerAttempts.asMap().compute(ipKey, (key, bucket) -> {
            val now = Instant.now(clock);
            if (bucket == null) {
                bucket = new ArrayDeque<>();
            }
            while (!bucket.isEmpty() && bucket.peekFirst().plus(REGISTER_WINDOW).isBefore(now)) {
                bucket.removeFirst();
            }
            if (bucket.size() < MAX_REGISTER_ATTEMPTS) {
                bucket.addLast(now);
                accepted.set(true);
            }
            return bucket;
        });
        return accepted.get();
    }

    private static class LoginState {
        private final Deque<Instant> failures = new ArrayDeque<>();
        private volatile Instant blockedUntil;

        private void prune(Instant now, Duration window) {
            while (!failures.isEmpty() && failures.peekFirst().plus(window).isBefore(now)) {
                failures.removeFirst();
            }
        }
    }
}
