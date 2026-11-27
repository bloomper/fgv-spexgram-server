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

package nu.fgv.spexgram.server.imports;

import nu.fgv.spexgram.server.util.Locales;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A record's message or warning in an import's result: a message code with its arguments, put in a language when the
 * result is read, so a caller can ask for it in each language (Spexipedia keeps both for its package reports). Every
 * text is in {@code messages_sv.properties} and {@code messages_en.properties}; an unexpected exception's details
 * are an argument of {@code import.failed.unexpected}.
 *
 * @param code the message code
 * @param args the arguments
 * @author Anders Jacobsson
 * @since 1.0
 */
public record ImportText(String code, List<String> args) {

    public static ImportText of(final String code, final Object @Nullable ... args) {
        return new ImportText(code, args == null ? List.of() : Arrays.stream(args).map(String::valueOf).toList());
    }

    /**
     * The text in the locale's language, Swedish unless English was asked for.
     */
    public String in(final MessageSource messages, final @Nullable Locale locale) {
        return messages.getMessage(code, args.toArray(), code, Locales.supported(locale));
    }
}
