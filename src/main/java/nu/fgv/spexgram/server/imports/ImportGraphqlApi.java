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

import nu.fgv.spexgram.server.imports.ImportEngine.ItemResult;
import nu.fgv.spexgram.server.imports.ImportEngine.Outcome;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GraphQL for the import API (spec 5.6.1). IMPORT only.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ImportGraphqlApi {

    private final SpexgramImport service;
    private final MessageSource messages;

    public ImportGraphqlApi(final SpexgramImport service, final MessageSource messages) {
        this.service = service;
        this.messages = messages;
    }

    @MutationMapping
    public ImportResult importBatch(@Argument final ImportRecords.Batch input, @Argument final boolean dryRun) {
        return ImportResult.of(dryRun, service.run(input, dryRun));
    }

    @MutationMapping
    public int importForget(@Argument final String sourcePrefix) {
        return service.forget(sourcePrefix);
    }

    /**
     * The record's message in the language asked for, else the request's.
     */
    @SchemaMapping(typeName = "ImportItem", field = "message")
    public @Nullable String message(final ItemResult item, @Argument final @Nullable String locale, final Locale requested) {
        return item.message() == null ? null : item.message().in(messages, locale(locale, requested));
    }

    /**
     * The record's warnings in the language asked for, else the request's.
     */
    @SchemaMapping(typeName = "ImportItem", field = "warnings")
    public List<String> warnings(final ItemResult item, @Argument final @Nullable String locale, final Locale requested) {
        final Locale in = locale(locale, requested);

        return item.warnings().stream().map(warning -> warning.in(messages, in)).toList();
    }

    private static Locale locale(final @Nullable String asked, final Locale requested) {
        return asked == null || asked.isBlank() ? requested : Locale.forLanguageTag(asked.strip());
    }

    public record ImportResult(boolean dryRun, List<ItemResult> items, int created, int updated, int unchanged,
                               int kept,
                               int failed) {

        public static ImportResult of(final boolean dryRun, final List<ItemResult> items) {
            final Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);

            items.forEach(item -> counts.merge(item.outcome(), 1, Integer::sum));
            return new ImportResult(dryRun, items, counts.getOrDefault(Outcome.CREATED, 0), counts.getOrDefault(Outcome.UPDATED, 0),
                    counts.getOrDefault(Outcome.UNCHANGED, 0), counts.getOrDefault(Outcome.KEPT, 0), counts.getOrDefault(Outcome.FAILED, 0));
        }
    }
}
