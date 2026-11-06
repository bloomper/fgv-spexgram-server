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

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import nu.fgv.spexgram.server.util.security.SecurityUtil;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the caller from the access token, and records people who log in so that admins can see who uses Spexgram. Records are deleted 36 months after the person was last seen (section 7 of the spec).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class CallerService {

    static final int RETENTION_MONTHS = 36;
    private static final String PREFIX = "ROLE_" + SecurityUtil.CLIENT_ID + "_";
    private static final Duration LAST_SEEN_RESOLUTION = Duration.ofMinutes(10);

    private final AppUserRepository repository;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public CallerService(final AppUserRepository repository, final Clock clock, final PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static Caller fromJwt(final Jwt jwt, final Collection<String> authorities) {
        final Set<Role> roles = EnumSet.noneOf(Role.class);

        authorities.stream()
                .filter(authority -> authority.startsWith(PREFIX))
                .map(authority -> authority.substring(PREFIX.length()))
                .forEach(role -> {
                    for (final Role candidate : Role.values()) {
                        if (candidate.name().equals(role)) {
                            roles.add(candidate);
                        }
                    }
                });

        // A service account's token may have no subject; it never acts as a user, so its client id stands in
        final String subject = Objects.requireNonNull(firstText(jwt.getSubject(), "client:" + jwt.getClaimAsString("azp")));
        final String username = Objects.requireNonNullElse(firstText(jwt.getClaimAsString("preferred_username"), jwt.getClaimAsString("email")), subject);

        return new Caller(subject, username, firstText(jwt.getClaimAsString("name"), username),
                firstText(jwt.getClaimAsString("email")), roles);
    }

    private static @Nullable String firstText(final @Nullable String... candidates) {
        for (final String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The caller, or an exception when there is no valid token.
     */
    public Caller current() {
        return currentIfAny().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("No access token"));
    }

    /**
     * The caller, or empty for a visitor without a token.
     */
    public Optional<Caller> currentIfAny() {
        return SecurityUtil.currentJwt().map(jwt -> fromJwt(jwt, SecurityUtil.currentAuthentication()
                .map(authentication -> authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList())
                .orElse(List.of())));
    }

    /**
     * Records the caller as a user, or refreshes their username, name, email and when they were last seen.
     */
    public AppUser record(final Caller caller) {
        try {
            return Objects.requireNonNull(transactionTemplate.execute(_ -> recordInTransaction(caller)));
        } catch (final DataIntegrityViolationException e) {
            // Two first requests from the same user, e.g. two GraphQL fields, raced to insert them; now the row exists
            return Objects.requireNonNull(transactionTemplate.execute(_ -> recordInTransaction(caller)));
        }
    }

    private AppUser recordInTransaction(final Caller caller) {
        final Instant now = clock.instant();
        final AppUser user = repository.findBySubject(caller.subject()).orElseGet(() -> {
            final AppUser created = new AppUser();

            created.setSubject(caller.subject());
            created.setCreatedAt(now);
            created.setLastSeenAt(now);
            return created;
        });

        if (user.getId() == null
                || !caller.username().equals(user.getUsername())
                || !Objects.equals(caller.displayName(), user.getDisplayName())
                || !Objects.equals(caller.email(), user.getEmail())
                || user.getLastSeenAt().plus(LAST_SEEN_RESOLUTION).isBefore(now)) {
            user.setUsername(caller.username());
            user.setDisplayName(caller.displayName());
            user.setEmail(caller.email());
            user.setLastSeenAt(now);
            return repository.save(user);
        }
        return user;
    }

    /**
     * Deletes the records of people who have not logged in for 36 months.
     */
    @SchedulerLock(name = "user-retention", lockAtMostFor = "PT10M")
    @Transactional
    public void cleanUp() {
        final Instant before = clock.instant().atZone(ZoneOffset.UTC).minusMonths(RETENTION_MONTHS).toInstant();
        final long deleted = repository.deleteByLastSeenAtBefore(before);

        if (deleted > 0) {
            log.info("Deleted {} users not seen for {} months", deleted, RETENTION_MONTHS);
        }
    }
}
