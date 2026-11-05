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
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Email addresses for notifications, read in Keycloak as {@code fgv-spexgram-service}, which has only
 * {@code realm-management}'s {@code view-users} and {@code view-clients}, which let it read who holds a role on
 * {@code fgv-spexgram}. Every failure, unreachable or refused, is a {@link DirectoryUnavailableException}; addresses
 * are never logged.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class UserDirectory {

    private static final int MAX_EDITORS = 200;

    private final Keycloak keycloak;
    private final String realm;

    public UserDirectory(final Keycloak keycloak, final SpexgramProperties properties) {
        this.keycloak = keycloak;
        this.realm = properties.keycloak().realm();
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
     * Editors and admins to tell of new reports: everyone holding {@code EDITOR} or {@code ADMIN} on
     * {@code fgv-spexgram} directly, enabled and with an email address, each once.
     */
    public List<Recipient> editors() {
        return call(() -> {
            final RealmResource realm = keycloak.realm(this.realm);
            final List<ClientRepresentation> clients = realm.clients().findByClientId(SecurityUtil.CLIENT_ID);

            if (clients.isEmpty()) {
                throw new IllegalStateException("The realm has no client " + SecurityUtil.CLIENT_ID);
            }

            final Map<String, Recipient> editors = new LinkedHashMap<>();

            for (final Role role : List.of(Role.EDITOR, Role.ADMIN)) {
                for (final UserRepresentation user : realm.clients().get(clients.getFirst().getId()).roles().get(role.name())
                        .getUserMembers(false, 0, MAX_EDITORS)) {
                    recipient(user).ifPresent(recipient -> editors.putIfAbsent(user.getId(), recipient));
                }
            }
            return List.copyOf(editors.values());
        });
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
