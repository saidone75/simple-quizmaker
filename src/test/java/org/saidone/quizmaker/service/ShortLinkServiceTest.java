package org.saidone.quizmaker.service;

import lombok.val;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.saidone.quizmaker.config.ShortLinkProperties;
import org.saidone.quizmaker.entity.ShortLink;
import org.saidone.quizmaker.repository.ShortLinkRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortLinkServiceTest {
    @Mock ShortLinkRepository repository;
    @Mock PlatformTransactionManager transactions;
    ShortLinkProperties properties;
    ShortLinkService service;

    @BeforeEach
    void setUp() {
        properties = new ShortLinkProperties();
        service = new ShortLinkService(repository, transactions, properties);
        service.initialize();
    }

    @Test
    void existingLinksRemainValidWhenGenerationRulesChange() {
        when(repository.findByTargetPath("/existing")).thenReturn(Optional.of(new ShortLink("OldCode12345A", "/existing")));
        when(repository.findById("OldCode12345A")).thenReturn(Optional.of(new ShortLink("OldCode12345A", "/existing")));
        assertThat(service.getOrCreate("/existing")).isEqualTo("OldCode12345A");
        assertThat(service.resolve("OldCode12345A")).contains("/existing");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void generationUsesConfiguredAlphabetAndLength() {
        properties.setAlphabet("0123456789");
        properties.setLength(10);
        service = new ShortLinkService(repository, transactions, properties);
        service.initialize();
        when(repository.findByTargetPath("/another-app/resource")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        assertThat(service.getOrCreate("/another-app/resource")).matches("[0-9]{10}");
        verify(repository).saveAndFlush(argThat(link -> link.isNew() && link.getTargetPath().equals("/another-app/resource")));
    }

    @Test
    void collisionIsRetriedOnlyAfterRollback() {
        val status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any())).thenReturn(status);
        when(repository.findByTargetPath("/resource")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("collision"))
                .thenAnswer(call -> call.getArgument(0));
        assertThat(service.getOrCreate("/resource")).matches("[a-z]{8}");
        val order = inOrder(repository, transactions);
        order.verify(transactions).getTransaction(any());
        order.verify(repository).findByTargetPath("/resource");
        order.verify(repository).saveAndFlush(any());
        order.verify(transactions).rollback(status);
        order.verify(transactions).getTransaction(any());
        order.verify(repository).findByTargetPath("/resource");
        order.verify(repository).saveAndFlush(any());
        order.verify(transactions).commit(status);
    }

    @Test
    void concurrentCreationReturnsTheExistingPathMappingOnRetry() {
        when(repository.findByTargetPath("/resource")).thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ShortLink("abcdefghijkl", "/resource")));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate target"));
        assertThat(service.getOrCreate("/resource")).isEqualTo("abcdefghijkl");
    }

    @Test
    void rejectsExternalTargetsAndInvalidCodesWithoutDatabaseAccess() {
        for (val path : new String[]{"https://example.org", "//example.org", "/\\example.org", "/bad\r\npath"}) {
            assertThatThrownBy(() -> service.getOrCreate(path)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(service.resolve("bad-code")).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void validatesGenerationRulesAtStartup() {
        properties.setAlphabet("aa");
        assertThatThrownBy(service::initialize).isInstanceOf(IllegalArgumentException.class);
        properties.setAlphabet("abc");
        properties.setLength(0);
        assertThatThrownBy(service::initialize).isInstanceOf(IllegalArgumentException.class);
    }
}
