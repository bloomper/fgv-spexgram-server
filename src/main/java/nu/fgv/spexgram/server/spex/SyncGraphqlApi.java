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

package nu.fgv.spexgram.server.spex;

import nu.fgv.spexgram.server.user.CallerService;
import nu.fgv.spexgram.server.util.StoredText;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.Locale;

/**
 * GraphQL for how the copy of Spexipedia's base data follows its change feed, for admins.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class SyncGraphqlApi {

    private final SpexipediaSync sync;
    private final CallerService callerService;
    private final MessageSource messages;

    public SyncGraphqlApi(final SpexipediaSync sync, final CallerService callerService, final MessageSource messages) {
        this.sync = sync;
        this.callerService = callerService;
        this.messages = messages;
    }

    @QueryMapping
    public SpexipediaSync.Status spexipediaSync() {
        callerService.current().requireAdmin();
        return sync.status();
    }

    /** Why the latest pull failed, in the reader's language. */
    @SchemaMapping(typeName = "SpexipediaSyncStatus", field = "lastError")
    public @Nullable String lastError(final SpexipediaSync.Status status, final Locale locale) {
        return status.lastError() == null ? null : StoredText.decode(status.lastError()).in(messages, locale);
    }

    /** Why a change was skipped, in the reader's language. */
    @SchemaMapping(typeName = "SpexipediaSyncSkipped", field = "error")
    public String error(final SpexipediaSync.Skipped skipped, final Locale locale) {
        return StoredText.decode(skipped.error()).in(messages, locale);
    }
}
