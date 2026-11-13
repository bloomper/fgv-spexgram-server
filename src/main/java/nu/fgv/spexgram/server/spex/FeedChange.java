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

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Spexipedia's change feed as it answers {@code baseDataChanges}, with the parts Spexgram copies.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public record FeedChange(String sequence, String kind, UUID id, String change, @Nullable UUID mergedIntoId,
                         @Nullable SpexCategory spexCategory, @Nullable Spex spex) {

    public record SpexCategory(UUID id, String name) {
    }

    public record Spex(UUID id, String year, String title, @Nullable UUID categoryId, @Nullable UUID parentId,
                       boolean retired) {
    }

    public record Page(List<FeedChange> changes, @Nullable String cursor, boolean hasMore) {
    }
}
