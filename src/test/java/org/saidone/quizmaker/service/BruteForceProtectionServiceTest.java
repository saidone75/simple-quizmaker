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
import lombok.val;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class BruteForceProtectionServiceTest {

    private final BruteForceProtectionService service = new BruteForceProtectionService();

    @Test
    void shouldBlockLoginAfterTooManyFailures() {
        val key = "teacher|127.0.0.1";

        for (int i = 0; i < BruteForceProtectionService.MAX_LOGIN_FAILURES; i++) {
            service.recordLoginFailure(key);
        }

        assertThat(service.isLoginBlocked(key)).isTrue();

        service.clearLoginFailures(key);

        assertThat(service.isLoginBlocked(key)).isFalse();
    }

    @Test
    void shouldLimitRegistrationAttemptsByIp() {
        val ip = "127.0.0.1";

        for (int i = 0; i < BruteForceProtectionService.MAX_REGISTER_ATTEMPTS; i++) {
            assertThat(service.consumeRegisterAttempt(ip)).isTrue();
        }

        assertThat(service.consumeRegisterAttempt(ip)).isFalse();
    }

    @Test
    void shouldApplySlidingWindowForStudentLoginKeyword() {
        val clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        val bruteForce = new BruteForceProtectionService(clock);
        val ip = "127.0.0.1";
        val keyword = "abcde";

        for (int i = 0; i < BruteForceProtectionService.MAX_STUDENT_LOGIN_FAILURES - 1; i++) {
            bruteForce.recordStudentLoginFailureByKeyword(keyword);
            clock.advance(Duration.ofMinutes(1));
        }

        assertThat(bruteForce.isStudentLoginBlocked(ip, keyword)).isFalse();

        clock.advance(BruteForceProtectionService.STUDENT_LOGIN_WINDOW.plusSeconds(1));
        bruteForce.recordStudentLoginFailureByKeyword(keyword);

        assertThat(bruteForce.isStudentLoginBlocked(ip, keyword)).isFalse();
    }

    @Test
    void shouldBlockStudentLoginByIpAndExpireShortLock() {
        val clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        val bruteForce = new BruteForceProtectionService(clock);
        val ip = "127.0.0.1";
        val keyword = "abcde";

        for (int i = 0; i < BruteForceProtectionService.MAX_STUDENT_LOGIN_FAILURES; i++) {
            bruteForce.recordStudentLoginFailureByIp(ip);
        }

        assertThat(bruteForce.isStudentLoginBlocked(ip, keyword)).isTrue();

        clock.advance(BruteForceProtectionService.STUDENT_LOGIN_LOCK.plusSeconds(1));

        assertThat(bruteForce.isStudentLoginBlocked(ip, keyword)).isFalse();
    }

    @Test
    void shouldReclaimExpiredKeysWithoutRevisitingThem() {
        val clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        val bruteForce = new BruteForceProtectionService(clock);
        for (int i = 0; i < 100; i++) {
            bruteForce.recordLoginFailure(String.format("teacher-%d", i));
            bruteForce.recordStudentLoginFailureByKeyword(String.format("keyword-%d", i));
            bruteForce.consumeRegisterAttempt(String.format("ip-%d", i));
        }

        clock.advance(Duration.ofMinutes(12));
        bruteForce.cleanupExpiredEntries();

        assertThat(cache(bruteForce, "loginStates").estimatedSize()).isZero();
        assertThat(cache(bruteForce, "registerAttempts").estimatedSize()).isZero();
    }

    @Test
    void shouldNotRetainExpiredLoginKeysWhenTheyArePolled() {
        val clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        val bruteForce = new BruteForceProtectionService(clock);
        bruteForce.recordLoginFailure("teacher");
        for (int i = 0; i < 7; i++) {
            clock.advance(Duration.ofMinutes(1));
            assertThat(bruteForce.isLoginBlocked("teacher")).isFalse();
        }
        bruteForce.cleanupExpiredEntries();
        assertThat(cache(bruteForce, "loginStates").estimatedSize()).isZero();
    }

    @Test
    void shouldBoundTrackedKeys() {
        val bruteForce = new BruteForceProtectionService();
        for (int i = 0; i < BruteForceProtectionService.MAX_TRACKED_KEYS * 2; i++) {
            bruteForce.recordLoginFailure(String.format("teacher-%d", i));
            bruteForce.consumeRegisterAttempt(String.format("ip-%d", i));
        }
        bruteForce.cleanupExpiredEntries();

        assertThat(cache(bruteForce, "loginStates").estimatedSize())
                .isLessThanOrEqualTo(BruteForceProtectionService.MAX_TRACKED_KEYS);
        assertThat(cache(bruteForce, "registerAttempts").estimatedSize())
                .isLessThanOrEqualTo(BruteForceProtectionService.MAX_TRACKED_KEYS);
    }

    @Test
    void shouldKeepTeacherBlockedForFullLockAndAllowLoginAfterwards() {
        val clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        val bruteForce = new BruteForceProtectionService(clock);
        for (int i = 0; i < BruteForceProtectionService.MAX_LOGIN_FAILURES; i++) {
            bruteForce.recordLoginFailure("teacher");
        }
        clock.advance(BruteForceProtectionService.LOGIN_LOCK.minusSeconds(1));
        bruteForce.cleanupExpiredEntries();
        assertThat(bruteForce.isLoginBlocked("teacher")).isTrue();

        clock.advance(Duration.ofSeconds(1));
        assertThat(bruteForce.isLoginBlocked("teacher")).isFalse();
        bruteForce.recordLoginFailure("teacher");
        assertThat(bruteForce.isLoginBlocked("teacher")).isFalse();
    }

    @Test
    void shouldPreserveRegistrationWindowAndAllowAttemptsAfterwards() {
        val clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
        val bruteForce = new BruteForceProtectionService(clock);
        for (int i = 0; i < BruteForceProtectionService.MAX_REGISTER_ATTEMPTS; i++) {
            assertThat(bruteForce.consumeRegisterAttempt("ip")).isTrue();
        }
        clock.advance(BruteForceProtectionService.REGISTER_WINDOW);
        bruteForce.cleanupExpiredEntries();
        assertThat(bruteForce.consumeRegisterAttempt("ip")).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(bruteForce.consumeRegisterAttempt("ip")).isTrue();
    }

    @Test
    void shouldEnforceRegistrationLimitForConcurrentRequests() {
        val accepted = IntStream.range(0, 1000).parallel()
                .filter(i -> service.consumeRegisterAttempt("same-ip")).count();
        assertThat(accepted).isEqualTo(BruteForceProtectionService.MAX_REGISTER_ATTEMPTS);
    }

    private static Cache<?, ?> cache(BruteForceProtectionService service, String name) {
        return (Cache<?, ?>) ReflectionTestUtils.getField(service, name);
    }

    private static class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
