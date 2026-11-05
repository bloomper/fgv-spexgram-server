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

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import nu.fgv.spexgram.server.config.SpexgramProperties;
import nu.fgv.spexgram.server.util.error.DirectoryUnavailableException;
import nu.fgv.spexgram.server.util.security.SecurityUtil;
import org.jspecify.annotations.Nullable;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.ClientMappingsRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Email addresses for notifications, read in Keycloak as {@code fgv-spexgram-service}, which has only
 * {@code realm-management}'s {@code view-users}. That role cannot list a client's role holders, so editors are found
 * among the people who have used Spexgram with {@code EDITOR} or {@code ADMIN} ({@link AppUser#isEditor()}) and
 * confirmed in Keycloak: enabled, with an email address and the role still held. Every failure, unreachable or refused,
 * is a {@link DirectoryUnavailableException}; addresses are never logged.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class UserDirectory {

    private static final Set<String> EDITOR_ROLES = Set.of(Role.EDITOR.name(), Role.ADMIN.name());

    private final Keycloak keycloak;
    private final String realm;
    private final AppUserRepository userRepository;

    public UserDirectory(final Keycloak keycloak, final SpexgramProperties properties, final AppUserRepository userRepository) {
        this.keycloak = keycloak;
        this.realm = properties.keycloak().realm();
        this.userRepository = userRepository;
    }

    private static <T> T call(final Supplier<T> action) {
        try {
            return action.get();
        } catch (final ProcessingException | WebApplicationException | IllegalStateException e) {
            throw new DirectoryUnavailableException(e);
        }
    }

    private static Optional<Recipient> recipient(final UserRepresentation user) {
        if (!Boolean.TRUE.equals(user.isEnabled()) || !StringUtils.hasText(user.getEmail())) {
            return Optional.empty();
        }

        final String locale = user.firstAttribute("locale");

        return Optional.of(new Recipient(user.getEmail(), user.getFirstName(),
                StringUtils.hasText(locale) ? Locale.forLanguageTag(locale) : null));
    }

    /**
     * Editors and admins to tell of new reports.
     */
    public List<Recipient> editors() {
        final List<Recipient> editors = new ArrayList<>();

        for (final AppUser candidate : userRepository.findByEditorTrue()) {
            call(() -> {
                final UserResource user = keycloak.realm(realm).users().get(candidate.getSubject());
                final MappingsRepresentation mappings = user.roles().getAll();
                final ClientMappingsRepresentation client = mappings.getClientMappings() == null ? null
                        : mappings.getClientMappings().get(SecurityUtil.CLIENT_ID);
                final boolean editor = client != null && client.getMappings() != null
                        && client.getMappings().stream().map(RoleRepresentation::getName).anyMatch(EDITOR_ROLES::contains);

                if (editor) {
                    recipient(user.toRepresentation()).ifPresent(editors::add);
                }
                return null;
            });
        }
        return editors;
    }

    /**
     * A member's address, by their Keycloak subject.
     */
    public Optional<Recipient> user(final String subject) {
        try {
            return call(() -> recipient(keycloak.realm(realm).users().get(subject).toRepresentation()));
        } catch (final DirectoryUnavailableException e) {
            if (e.getCause() instanceof final WebApplicationException web && web.getResponse().getStatus() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Someone to email, in their language when Keycloak knows it.
     */
    public record Recipient(String email, @Nullable String name, @Nullable Locale locale) {
    }
}
