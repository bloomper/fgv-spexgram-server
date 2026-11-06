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

import nu.fgv.spexgram.server.album.AlbumKindService.AlbumKindDto;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Albums as the GraphQL API shows and takes them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class AlbumDtos {

    private AlbumDtos() {
    }

    /**
     * An album. Its cover, item count and items depend on the viewer and are resolved per field.
     *
     * @param suggested   whether a member suggested it and it waits for review
     * @param coverItemId the chosen cover
     */
    public record AlbumDto(UUID id, String title, AlbumKindDto kind, List<AlbumSpexDto> spex, @Nullable String dateFrom,
                           @Nullable String dateTo, @Nullable String place, @Nullable String description,
                           Visibility visibility, boolean suggested, @Nullable Long coverItemId,
                           @Nullable Instant trashedAt, Instant createdAt, @Nullable Instant lastModifiedAt) {
    }

    /**
     * A spex of an album, with how many albums the viewer sees of it where that is asked for.
     */
    public record AlbumSpexDto(UUID id, @Nullable String title, @Nullable Integer year, @Nullable Boolean revival,
                               @Nullable UUID parentId, UUID familyId, @Nullable UUID categoryId,
                               @Nullable String categoryName, @Nullable Integer albumCount) {

        static AlbumSpexDto of(final SpexRef ref, final @Nullable Integer albumCount) {
            return new AlbumSpexDto(ref.getId(), ref.getTitle(), ref.getYear(), ref.getRevival(), ref.getParentId(),
                    ref.familyId(), ref.getCategoryId(), ref.getCategoryName(), albumCount);
        }
    }

    public record AlbumRef(UUID id, String title, Visibility visibility) {
    }

    public record AlbumPage(List<AlbumDto> albums, long totalCount, int page, int size) {
    }

    public record AlbumFilter(@Nullable UUID categoryId, @Nullable Integer year, @Nullable UUID spexId,
                              @Nullable UUID kindId, @Nullable Visibility visibility) {
    }

    public record AlbumInput(@Nullable List<UUID> spexIds, @Nullable String title, @Nullable UUID kindId,
                             @Nullable String dateFrom, @Nullable String dateTo, @Nullable String place,
                             @Nullable String description) {
    }

    public record CategoryDto(UUID id, String name, int albumCount) {
    }

    public record VisibilityCount(Visibility visibility, int count) {
    }

    /**
     * @param skipped how many items kept their visibility because an open report hides them
     */
    public record AlbumVisibilityResult(AlbumDto album, int skipped) {
    }
}
