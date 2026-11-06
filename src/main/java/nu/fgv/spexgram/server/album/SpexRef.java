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


package nu.fgv.spexgram.server.album;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * A spex an album is about, by its id in Spexregister, with its title, year and category copied so that the
 * archive works while Spexregister is unavailable. Refreshed daily. A revival keeps the id of its original.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Embeddable
@Getter
@Setter
public class SpexRef {

    @Column(name = "spex_id", nullable = false, length = 36)
    private UUID id;

    @Column(name = "parent_id", length = 36)
    private @Nullable UUID parentId;

    @Column(name = "title")
    private @Nullable String title;

    @Column(name = "year")
    private @Nullable Integer year;

    @Column(name = "revival")
    private @Nullable Boolean revival;

    @Column(name = "category_id", length = 36)
    private @Nullable UUID categoryId;

    @Column(name = "category_name")
    private @Nullable String categoryName;

    @Column(name = "refreshed_at")
    private @Nullable Instant refreshedAt;

    /**
     * The original spex: the parent of a revival, else the spex itself. Revivals share it with their original.
     */
    public UUID familyId() {
        return parentId != null ? parentId : id;
    }
}
