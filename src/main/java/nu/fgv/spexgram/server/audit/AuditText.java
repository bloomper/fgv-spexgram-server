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

package nu.fgv.spexgram.server.audit;

import nu.fgv.spexgram.server.util.Locales;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * An audit entry's summary: a message code of {@code messages*.properties} with its arguments, put in the reader's
 * language when the log is read. An argument is a name or title as it is, or another text, e.g. how an album's
 * visibility was changed. Stored as JSON in the summary column, so the log's search finds the names and titles;
 * entries written before summaries were codes are shown as they are.
 *
 * @param code the message code
 * @param args the arguments: {@link String}, {@link Long} (a count, for plural forms) or {@link AuditText}
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record AuditText(String code, List<Object> args) {

    static final String STORED = "audit.stored";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ARG = 300;

    public AuditText {
        args = List.copyOf(args);
    }

    /** A text; whole numbers are kept as numbers, other arguments that are not texts as strings ({@code null} as empty). */
    public static AuditText of(final String code, final @Nullable Object... args) {
        return new AuditText(code, args == null ? List.of() : Arrays.stream(args).map(AuditText::arg).toList());
    }

    private static Object arg(final @Nullable Object arg) {
        return switch (arg) {
            case final AuditText text -> text;
            case final Integer n -> n.longValue();
            case final Long n -> n;
            case null -> "";
            default -> String.valueOf(arg);
        };
    }

    /** The summary in the locale's language, Swedish unless English was asked for. */
    public String in(final MessageSource messages, final @Nullable Locale locale) {
        if (STORED.equals(code)) {
            return args.isEmpty() ? "" : String.valueOf(args.getFirst());
        }

        final Object[] rendered = args.stream().map(arg -> arg instanceof AuditText text ? text.in(messages, locale) : arg).toArray();

        return messages.getMessage(code, rendered, code, Locales.supported(locale));
    }

    /** For the summary column; long arguments are cut so it fits in {@code max} characters. */
    public String encode(final int max) {
        final String full = JSON.writeValueAsString(node(Integer.MAX_VALUE));

        return full.length() <= max ? full : JSON.writeValueAsString(node(MAX_ARG));
    }

    /** What {@link #encode} wrote; any other text is a summary from before, shown as it is. */
    public static AuditText decode(final String stored) {
        if (stored.startsWith("{\"c\":")) {
            try {
                return of(JSON.readTree(stored));
            } catch (final RuntimeException e) {
                // Not one of ours after all
            }
        }
        return new AuditText(STORED, List.of(stored));
    }

    private ObjectNode node(final int maxArg) {
        final ObjectNode node = JSON.createObjectNode();
        final ArrayNode array = node.put("c", code).putArray("a");

        for (final Object arg : args) {
            if (arg instanceof AuditText text) {
                array.add(text.node(maxArg));
            } else if (arg instanceof Long n) {
                array.add(n);
            } else {
                final String value = String.valueOf(arg);

                array.add(value.length() > maxArg ? value.substring(0, maxArg) + "…" : value);
            }
        }
        return node;
    }

    private static AuditText of(final JsonNode node) {
        final List<Object> args = new ArrayList<>();

        for (final JsonNode arg : node.get("a")) {
            args.add(arg.isObject() ? of(arg) : arg.isIntegralNumber() ? (Object) arg.asLong() : arg.asString());
        }
        return new AuditText(node.get("c").asString(), args);
    }
}
