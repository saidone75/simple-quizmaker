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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.saidone.quizmaker.entity.Teacher;
import org.saidone.quizmaker.repository.TeacherRepository;
import org.springframework.security.crypto.password.PasswordEncoder;


import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherAdministrationServiceTest {

    @Mock
    private TeacherRepository teacherRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SessionRegistry sessionRegistry;

    @InjectMocks
    private TeacherAdministrationService teacherAdministrationService;

    private Teacher actingAdmin;

    @BeforeEach
    void setUp() {
        actingAdmin = Teacher.builder()
                .id(UUID.randomUUID())
                .username("admin")
                .admin(true)
                .enabled(true)
                .build();
    }

    @Test
    void approveTeacherRegistration_shouldEnableTeacherAndClearPendingFlag() {
        val targetId = UUID.randomUUID();
        val pendingTeacher = Teacher.builder()
                .id(targetId)
                .username("teacher")
                .enabled(false)
                .approvalPending(true)
                .build();
        when(teacherRepository.findById(targetId)).thenReturn(Optional.of(pendingTeacher));

        teacherAdministrationService.approveTeacherRegistration(targetId, actingAdmin);

        assertThat(pendingTeacher.isApprovalPending()).isFalse();
        assertThat(pendingTeacher.isEnabled()).isTrue();
        verify(teacherRepository).save(pendingTeacher);
    }

    @Test
    void approveAllPendingRegistrations_shouldApproveEveryPendingTeacher() {
        val first = Teacher.builder().id(UUID.randomUUID()).enabled(false).approvalPending(true).build();
        val second = Teacher.builder().id(UUID.randomUUID()).enabled(false).approvalPending(true).build();
        when(teacherRepository.findAllByApprovalPendingTrueOrderByCreatedAtAsc()).thenReturn(List.of(first, second));

        val approvedCount = teacherAdministrationService.approveAllPendingRegistrations(actingAdmin);

        assertThat(approvedCount).isEqualTo(2);
        assertThat(first.isApprovalPending()).isFalse();
        assertThat(second.isApprovalPending()).isFalse();
        assertThat(first.isEnabled()).isTrue();
        assertThat(second.isEnabled()).isTrue();
        verify(teacherRepository).saveAll(List.of(first, second));
    }

    @Test
    void disablingTeacherExpiresAllTheirSessionsAfterCommit() {
        assertSessionsExpiredAfterCommit(false);
    }

    @Test
    void changingAdminRoleExpiresAllTheirSessionsAfterCommit() {
        assertSessionsExpiredAfterCommit(true);
    }

    @Test
    void rollbackDoesNotExpireTeacherSessions() {
        val target = Teacher.builder().id(UUID.randomUUID()).username("teacher").enabled(true).build();
        when(teacherRepository.findById(target.getId())).thenReturn(Optional.of(target));
        TransactionSynchronizationManager.initSynchronization();
        try {
            teacherAdministrationService.updateTeacherEnabledFlag(target.getId(), false, actingAdmin);
            TransactionSynchronizationManager.getSynchronizations().forEach(synchronization ->
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            org.mockito.Mockito.verifyNoInteractions(sessionRegistry);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void assertSessionsExpiredAfterCommit(boolean changeRole) {
        val target = Teacher.builder().id(UUID.randomUUID()).username("teacher").admin(true).enabled(true).build();
        val principal = User.withUsername("teacher")
                .password("unused").roles("TEACHER", "ADMIN").build();
        val first = new SessionInformation(principal, "first", new Date());
        val second = new SessionInformation(principal, "second", new Date());
        when(teacherRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(sessionRegistry.getAllPrincipals()).thenReturn(List.of(principal));
        when(sessionRegistry.getAllSessions(principal, false)).thenReturn(List.of(first, second));
        TransactionSynchronizationManager.initSynchronization();
        try {
            if (changeRole) teacherAdministrationService.updateTeacherAdminFlag(target.getId(), false, actingAdmin);
            else teacherAdministrationService.updateTeacherEnabledFlag(target.getId(), false, actingAdmin);
            assertThat(first.isExpired()).isFalse();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(first.isExpired()).isTrue();
            assertThat(second.isExpired()).isTrue();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

}
