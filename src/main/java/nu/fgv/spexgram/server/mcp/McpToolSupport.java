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

package nu.fgv.spexgram.server.mcp;

import nu.fgv.spexgram.server.config.SpexgramProperties;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import nu.fgv.spexgram.server.visibility.Viewer;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Shared by the MCP tools, trimmed from Nyuppsättning's: the viewer every tool reads as, refusals as readable messages,
 * links to the web and the result record. Nothing here changes anything, so there is no audit stamp.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class McpToolSupport {

    /**
     * Every tool reads as a visitor: public albums only, whatever roles the caller has, since photos show people.
     */
    static final Viewer VIEWER = Viewer.visitor();

    private final MessageSource messageSource;
    private final String webUrl;

    public McpToolSupport(final MessageSource messageSource, final SpexgramProperties properties) {
        this.messageSource = messageSource;
        this.webUrl = Objects.requireNonNullElse(properties.webUrl(), "").replaceAll("/+$", "");
    }

    /**
     * An id from a tool argument.
     */
    static UUID id(final String value) {
        try {
            return UUID.fromString(value.strip());
        } catch (final IllegalArgumentException e) {
            throw new BadRequestException("error.mcp.id", value);
        }
    }

    /**
     * The address of a page in Spexgram Web, e.g. {@code /albums/<id>}, or null when the web's address is not known.
     */
    @Nullable String link(final String path) {
        return StringUtils.hasText(webUrl) ? webUrl + path : null;
    }

    /**
     * Runs a tool. A refusal becomes a tool error with the API's error code and the same English message the web shows,
     * e.g. "NOT_FOUND: The album does not exist.".
     */
    <T> T call(final Supplier<T> body) {
        try {
            return body.get();
        } catch (final AccessDeniedException e) {
            throw refusal("FORBIDDEN", "error.forbidden", null, e);
        } catch (final BadRequestException e) {
            throw refusal("BAD_REQUEST", e);
        } catch (final ResourceNotFoundException e) {
            throw refusal("NOT_FOUND", e);
        }
    }

    private McpToolRefusal refusal(final String code, final ResponseStatusException e) {
        return refusal(code, e.getDetailMessageCode(), e.getDetailMessageArguments(), e);
    }

    private McpToolRefusal refusal(final String code, final @Nullable String messageCode, final Object @Nullable [] arguments,
                                   final Exception cause) {
        final String message = messageCode == null ? code : messageSource.getMessage(messageCode, arguments, messageCode, Locale.ENGLISH);

        return new McpToolRefusal("%s: %s".formatted(code, message), cause);
    }

    static final class McpToolRefusal extends RuntimeException {

        McpToolRefusal(final String message, final Throwable cause) {
            super(message, cause);
        }
    }

    public record ListResult<T>(List<T> items) {
    }
}
