/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nu.fgv.spexgram.server.user;

import nu.fgv.spexgram.server.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who the caller is, from the access token, and how they are recorded.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class CallerServiceTest {

    private final TestClock clock = new TestClock(Instant.parse("2026-11-01T10:00:00Z"));
    @Mock
    private AppUserRepository repository;
    @Mock
    private PlatformTransactionManager transactionManager;
    private CallerService service;

    private static Jwt jwt(final String subject, final String username) {
        final Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").claim("azp", "fgv-spexgram")
                .claim("preferred_username", username).claim("name", "Lisa Lyssnare").claim("email", "lisa@spexgram.test");

        return (subject == null ? builder : builder.subject(subject)).build();
    }

    @BeforeEach
    void setUp() {
        service = new CallerService(repository, clock, transactionManager);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void rolesComeFromTheClientsAuthoritiesOnly() {
        final Caller caller = CallerService.fromJwt(jwt("s-1", "lisa@spexgram.test"),
                List.of("ROLE_fgv-spexgram_USER", "ROLE_fgv-spexgram_EDITOR", "ROLE_fgv-spexgram_UNKNOWN", "ROLE_spexregister_ADMIN"));

        assertThat(caller.roles()).containsExactlyInAnyOrder(Role.USER, Role.EDITOR);
        assertThat(caller.username()).isEqualTo("lisa@spexgram.test");
        assertThat(caller.displayName()).isEqualTo("Lisa Lyssnare");
        assertThat(caller.email()).isEqualTo("lisa@spexgram.test");
    }

    @Test
    void aServiceAccountWithoutSubjectIsNamedByItsClient() {
        final Caller caller = CallerService.fromJwt(jwt(null, null), List.of("ROLE_fgv-spexgram_SPEXIPEDIA"));

        assertThat(caller.subject()).isEqualTo("client:fgv-spexgram");
        assertThat(caller.isSpexipediaService()).isTrue();
        assertThat(caller.isUser()).isFalse();
    }

    @Test
    void withoutATokenTheCallerIsUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> service.current()).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThat(service.currentIfAny()).isEmpty();
    }

    @Test
    void aFirstCallerIsRecordedAndARaceWithAnotherRequestIsRetried() {
        final Caller caller = new Caller("s-1", "lisa", "Lisa", null, Set.of(Role.USER));
        final AppUser existing = new AppUser();

        existing.setId(1L);
        existing.setSubject("s-1");
        existing.setUsername("lisa");
        existing.setDisplayName("Lisa");
        existing.setLastSeenAt(clock.instant());
        when(repository.findBySubject("s-1")).thenReturn(Optional.empty()).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThat(service.record(caller)).isSameAs(existing);
        verify(repository, times(1)).save(any());
    }

    @Test
    void aKnownCallerIsSavedOnlyWhenSomethingChangedOrTenMinutesPassed() {
        final AppUser existing = new AppUser();

        existing.setId(1L);
        existing.setSubject("s-1");
        existing.setUsername("lisa");
        existing.setDisplayName("Lisa");
        existing.setLastSeenAt(clock.instant());
        when(repository.findBySubject("s-1")).thenReturn(Optional.of(existing));

        service.record(new Caller("s-1", "lisa", "Lisa", null, Set.of(Role.USER)));
        verify(repository, never()).save(any());

        service.record(new Caller("s-1", "lisa", "Lisa Lyssnare", null, Set.of(Role.USER)));
        clock.advance(Duration.ofMinutes(11));
        service.record(new Caller("s-1", "lisa", "Lisa Lyssnare", null, Set.of(Role.USER)));
        verify(repository, times(2)).save(existing);
        assertThat(existing.getDisplayName()).isEqualTo("Lisa Lyssnare");
        assertThat(existing.getLastSeenAt()).isEqualTo(clock.instant());
    }
}
