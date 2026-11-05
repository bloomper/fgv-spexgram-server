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

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * What to show of the audit log; every part is optional.
 *
 * @param entityType what was changed, e.g. ALBUM
 * @param action     CREATE, UPDATE, DELETE and so on
 * @param actorId    who changed it, by their subject; only ADMIN sees others' changes
 * @param from       changes from this instant on
 * @param to         changes before this instant
 * @param q          text the summary contains
 * @param entityId   one record's changes
 * @author Anders Jacobsson
 * @since 1.0
 */
public record AuditFilter(@Nullable String entityType, @Nullable String action, @Nullable String actorId,
                          @Nullable Instant from, @Nullable Instant to, @Nullable String q, @Nullable String entityId) {
}
