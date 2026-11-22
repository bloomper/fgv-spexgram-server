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

package nu.fgv.spexgram.server.util;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class StoredTextTest {

    private static final ResourceBundleMessageSource MESSAGES = new ResourceBundleMessageSource();
    private static final Locale SWEDISH = Locale.of("sv");

    static {
        MESSAGES.setBasename("messages");
        MESSAGES.setDefaultEncoding("UTF-8");
        MESSAGES.setFallbackToSystemLocale(false);
    }

    @Test
    void inTheReadersLanguageSwedishUnlessEnglish() {
        final StoredText text = StoredText.of("audit.settings.update");

        assertThat(text.in(MESSAGES, SWEDISH)).isEqualTo("Ändrade inställningarna");
        assertThat(text.in(MESSAGES, null)).isEqualTo("Ändrade inställningarna");
        assertThat(text.in(MESSAGES, Locale.ENGLISH)).isEqualTo("Changed the settings");
        // A kind's own name in each language
        assertThat(StoredText.of("audit.name", "Sång", "Song").in(MESSAGES, SWEDISH)).isEqualTo("Sång");
        assertThat(StoredText.of("audit.name", "Sång", "Song").in(MESSAGES, Locale.ENGLISH)).isEqualTo("Song");
    }

    @Test
    void everyTextHasBothLanguages() {
        assertThat(properties("sv").stringPropertyNames().stream().sorted().toList())
                .isNotEmpty()
                .isEqualTo(properties("en").stringPropertyNames().stream().sorted().toList());
    }

    @Test
    void everyStoredTextFormatsInBothLanguages() {
        for (final Locale locale : List.of(SWEDISH, Locale.ENGLISH)) {
            for (final String key : properties(locale.getLanguage()).stringPropertyNames()) {
                if (key.startsWith("audit.") || key.startsWith("text.")) {
                    for (final long count : new long[]{0, 1, 2}) {
                        assertThat(MESSAGES.getMessage(key, new Object[]{count, count, count}, locale)).doesNotContain("{0").doesNotContain("{1");
                    }
                }
            }
        }
    }

    @Test
    void storedAsJsonWithItsArgumentsAndReadBack() {
        final StoredText text = StoredText.of("audit.name", StoredText.of("audit.settings.update"), 3, "Ett \"citat\" {0} 'x'");
        final String stored = text.encode(1000);

        assertThat(stored).startsWith("{\"c\":\"audit.name\"").contains("citat");
        assertThat(StoredText.decode(stored)).isEqualTo(text);
        assertThat(StoredText.decode(stored).args().get(1)).isEqualTo(3L);
    }

    @Test
    void oldSummariesAreShownAsTheyAre() {
        assertThat(StoredText.decode("Ändrade inställningarna").in(MESSAGES, Locale.ENGLISH)).isEqualTo("Ändrade inställningarna");
        assertThat(StoredText.decode("{\"c\": broken").in(MESSAGES, Locale.ENGLISH)).isEqualTo("{\"c\": broken");
    }

    @Test
    void longArgumentsAreCutToFit() {
        final String stored = StoredText.of("audit.name", "x".repeat(5000), "y").encode(1000);

        assertThat(stored.length()).isLessThanOrEqualTo(1000);
        assertThat(StoredText.decode(stored).args()).hasSize(2);
        assertThat(List.of(stored)).allMatch(s -> s.contains("…"));
    }

    private static java.util.Properties properties(final String language) {
        final java.util.Properties properties = new java.util.Properties();

        try (var in = StoredTextTest.class.getResourceAsStream("/messages_" + language + ".properties");
             var reader = new java.io.InputStreamReader(java.util.Objects.requireNonNull(in), java.nio.charset.StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (final java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return properties;
    }
}
