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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BruteForceProtectionServiceTest {

    private final BruteForceProtectionService service = new BruteForceProtectionService();

    @Test
    void shouldBlockLoginAfterTooManyFailures() {
        String key = "teacher|127.0.0.1";

        for (int i = 0; i < BruteForceProtectionService.MAX_LOGIN_FAILURES; i++) {
            service.recordLoginFailure(key);
        }

        assertThat(service.isLoginBlocked(key)).isTrue();

        service.clearLoginFailures(key);

        assertThat(service.isLoginBlocked(key)).isFalse();
    }

    @Test
    void shouldLimitRegistrationAttemptsByIp() {
        String ip = "127.0.0.1";

        for (int i = 0; i < BruteForceProtectionService.MAX_REGISTER_ATTEMPTS; i++) {
            assertThat(service.consumeRegisterAttempt(ip)).isTrue();
        }

        assertThat(service.consumeRegisterAttempt(ip)).isFalse();
    }
}
