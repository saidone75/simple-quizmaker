/*
 * QuizMaker - fun quizzes for curious minds
 * Copyright (C) 2026 Saidone
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.saidone.quizmaker.service;

import lombok.val;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BruteForceProtectionService {

    static final int MAX_LOGIN_FAILURES = 5;
    static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    static final Duration LOGIN_LOCK = Duration.ofMinutes(20);

    static final int MAX_REGISTER_ATTEMPTS = 10;
    static final Duration REGISTER_WINDOW = Duration.ofMinutes(10);

    private final Map<String, LoginState> loginStates = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> registerAttempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public BruteForceProtectionService() {
        this(Clock.systemUTC());
    }

    BruteForceProtectionService(Clock clock) {
        this.clock = clock;
    }

    public boolean isLoginBlocked(String key) {
        val state = loginStates.get(key);
        if (state == null) {
            return false;
        }

        val now = Instant.now(clock);
        if (state.blockedUntil != null && state.blockedUntil.isAfter(now)) {
            return true;
        }

        if (state.blockedUntil != null && !state.blockedUntil.isAfter(now)) {
            loginStates.remove(key);
        }
        return false;
    }

    public void recordLoginFailure(String key) {
        val now = Instant.now(clock);
        loginStates.compute(key, (k, state) -> {
            if (state == null || state.windowStart.plus(LOGIN_WINDOW).isBefore(now)) {
                state = new LoginState();
                state.windowStart = now;
                state.failures = 0;
            }

            if (state.blockedUntil != null && state.blockedUntil.isAfter(now)) {
                return state;
            }

            state.failures++;
            if (state.failures >= MAX_LOGIN_FAILURES) {
                state.blockedUntil = now.plus(LOGIN_LOCK);
            }
            return state;
        });
    }

    public void clearLoginFailures(String key) {
        loginStates.remove(key);
    }

    public boolean consumeRegisterAttempt(String ipKey) {
        val now = Instant.now(clock);
        val bucket = registerAttempts.computeIfAbsent(ipKey, k -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst().plus(REGISTER_WINDOW).isBefore(now)) {
                bucket.removeFirst();
            }
            if (bucket.size() >= MAX_REGISTER_ATTEMPTS) {
                return false;
            }
            bucket.addLast(now);
            return true;
        }
    }

    private static class LoginState {
        private int failures;
        private Instant windowStart;
        private Instant blockedUntil;
    }
}
