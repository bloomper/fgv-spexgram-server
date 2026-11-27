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

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The records Spexgram's import API accepts (spec 5.6.1). Album kinds are given by name or alias; spex by their
 * Spexipedia ids; an item's album by the source key of an album record.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class ImportRecords {

    private ImportRecords() {
    }

    public record FileRef(String sha256, @Nullable String name) {
    }

    public record Batch(@Nullable Boolean createMissingKinds, @Nullable List<ImportAlbum> albums,
                        @Nullable List<ImportItem> items) {
    }

    public record ImportAlbum(String sourceKey, @Nullable List<UUID> spexIds, @Nullable String kind,
                              @Nullable String title,
                              @Nullable String dateFrom, @Nullable String dateTo, @Nullable String place,
                              @Nullable String description) {
    }

    /**
     * A photo or a short video of an album imported with albumSourceKey; takenAt (yyyy-mm-ddThh:mm or yyyy-mm-dd) only
     * when the file's own metadata has none worth keeping, e.g. a scan.
     */
    public record ImportItem(String sourceKey, String albumSourceKey, FileRef file, @Nullable String caption,
                             @Nullable String photographer, @Nullable String takenAt) {
    }
}
