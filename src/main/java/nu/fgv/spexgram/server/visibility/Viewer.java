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


package nu.fgv.spexgram.server.visibility;

import nu.fgv.spexgram.server.user.Caller;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;

import java.util.EnumSet;
import java.util.Set;

/**
 * Who is looking, as far as what they may see goes. Every query decides through it: visitors (no token, or a token
 * without {@code USER}) see public albums and items only, members also members' ones, editors everything.
 * Spexipedia's service account sees public and members' items, but only through the queries for Spexipedia; elsewhere
 * it is a visitor.
 *
 * @param kind   what the viewer may see
 * @param caller the caller, or null for a visitor without a token
 * @author Anders Jacobsson
 * @since 1.0
 */
public record Viewer(Kind kind, @Nullable Caller caller) {

    private static final Set<Visibility> PUBLIC_ONLY = EnumSet.of(Visibility.PUBLIC);
    private static final Set<Visibility> MEMBERS_AND_PUBLIC = EnumSet.of(Visibility.MEMBERS, Visibility.PUBLIC);

    public static Viewer visitor() {
        return new Viewer(Kind.VISITOR, null);
    }

    /**
     * The visibilities whose albums and items the viewer sees.
     */
    public Set<Visibility> sees() {
        return switch (kind) {
            case VISITOR -> PUBLIC_ONLY;
            case SPEXIPEDIA, USER -> MEMBERS_AND_PUBLIC;
            case EDITOR, ADMIN -> EnumSet.allOf(Visibility.class);
        };
    }

    public boolean sees(final Visibility visibility) {
        return sees().contains(visibility);
    }

    /**
     * Logged in with {@code USER}: sees members' albums, contributes and reports as themselves.
     */
    public boolean isMember() {
        return kind == Kind.USER || kind == Kind.EDITOR || kind == Kind.ADMIN;
    }

    public boolean isEditor() {
        return kind == Kind.EDITOR || kind == Kind.ADMIN;
    }

    public boolean isAdmin() {
        return kind == Kind.ADMIN;
    }

    /**
     * The member's Keycloak subject, or null for visitors and Spexipedia.
     */
    public @Nullable String subject() {
        return isMember() && caller != null ? caller.subject() : null;
    }

    public Caller requireMember() {
        if (!isMember() || caller == null) {
            throw new AccessDeniedException("USER required");
        }
        return caller;
    }

    public Caller requireEditor() {
        if (!isEditor() || caller == null) {
            throw new AccessDeniedException("EDITOR required");
        }
        return caller;
    }

    public Caller requireAdmin() {
        if (!isAdmin() || caller == null) {
            throw new AccessDeniedException("ADMIN required");
        }
        return caller;
    }

    public enum Kind {
        VISITOR,
        SPEXIPEDIA,
        USER,
        EDITOR,
        ADMIN
    }
}
