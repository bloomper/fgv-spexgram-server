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

import nu.fgv.spexgram.server.album.AlbumDtos.AlbumDto;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumFilter;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumInput;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumPage;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumSpexDto;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumVisibilityResult;
import nu.fgv.spexgram.server.album.AlbumDtos.CategoryDto;
import nu.fgv.spexgram.server.album.AlbumDtos.VisibilityCount;
import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.item.ItemDtos.ItemPage;
import nu.fgv.spexgram.server.item.ItemService;
import nu.fgv.spexgram.server.spex.SpexDto;
import nu.fgv.spexgram.server.spex.SpexService;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GraphQL for albums. A thin adapter: the services decide what the viewer may see and do.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class AlbumGraphqlApi {

    private final AlbumService service;
    private final ItemService itemService;
    private final SpexService spexService;

    public AlbumGraphqlApi(final AlbumService service, final ItemService itemService, final SpexService spexService) {
        this.service = service;
        this.itemService = itemService;
        this.spexService = spexService;
    }

    @QueryMapping
    public AlbumPage albums(@Argument final @Nullable AlbumFilter filter, @Argument final @Nullable Integer page,
                            @Argument final @Nullable Integer size) {
        return service.albums(filter, page == null ? 0 : page, size == null ? 50 : size);
    }

    @QueryMapping
    public @Nullable AlbumDto album(@Argument final UUID id) {
        return service.album(id);
    }

    @QueryMapping
    public List<AlbumDto> recentAlbums(@Argument final @Nullable Integer limit) {
        return service.recent(limit == null ? 12 : limit);
    }

    @QueryMapping
    public List<AlbumDto> spexAlbums(@Argument final UUID spexId) {
        return service.spexAlbums(spexId);
    }

    @QueryMapping
    public List<CategoryDto> categories() {
        return service.categories();
    }

    @QueryMapping
    public List<Integer> years() {
        return service.years();
    }

    @QueryMapping
    public List<AlbumSpexDto> spexWithAlbums() {
        return service.spexWithAlbums();
    }

    @QueryMapping
    public List<SpexDto> spexSearch(@Argument final String q) {
        return spexService.search(q);
    }

    @BatchMapping(typeName = "Album", field = "cover")
    public Map<AlbumDto, @Nullable ItemDto> cover(final List<AlbumDto> albums) {
        final Map<UUID, ItemDto> covers = itemService.covers(albums.stream().map(AlbumDto::id).toList());
        final Map<AlbumDto, @Nullable ItemDto> result = new LinkedHashMap<>();

        albums.forEach(album -> result.put(album, covers.get(album.id())));
        return result;
    }

    @BatchMapping(typeName = "Album", field = "itemCount")
    public Map<AlbumDto, Integer> itemCount(final List<AlbumDto> albums) {
        final Map<UUID, Integer> counts = itemService.itemCounts(albums.stream().map(AlbumDto::id).toList());
        final Map<AlbumDto, Integer> result = new LinkedHashMap<>();

        albums.forEach(album -> result.put(album, counts.getOrDefault(album.id(), 0)));
        return result;
    }

    @SchemaMapping(typeName = "Album", field = "items")
    public ItemPage items(final AlbumDto album, @Argument final @Nullable Integer page, @Argument final @Nullable Integer size) {
        return itemService.albumItems(album.id(), page == null ? 0 : page, size == null ? 100 : size);
    }

    @SchemaMapping(typeName = "Album", field = "visibilityCounts")
    public List<VisibilityCount> visibilityCounts(final AlbumDto album) {
        return itemService.visibilityCounts(album.id());
    }

    @MutationMapping
    public AlbumDto albumCreate(@Argument final AlbumInput input) {
        return service.create(input);
    }

    @MutationMapping
    public AlbumDto albumUpdate(@Argument final UUID id, @Argument final AlbumInput input) {
        return service.update(id, input);
    }

    @MutationMapping
    public AlbumVisibilityResult albumSetVisibility(@Argument final UUID id, @Argument final Visibility visibility, @Argument final @Nullable Boolean items) {
        return service.setVisibility(id, visibility, Boolean.TRUE.equals(items));
    }

    @MutationMapping
    public AlbumDto albumSetCover(@Argument final UUID id, @Argument final @Nullable UUID itemId) {
        return service.setCover(id, itemId);
    }

    @MutationMapping
    public AlbumDto albumTrash(@Argument final UUID id) {
        return service.trash(id);
    }

    @MutationMapping
    public AlbumDto albumRestore(@Argument final UUID id) {
        return service.restore(id);
    }

    @MutationMapping
    public @Nullable Void albumDelete(@Argument final UUID id, @Argument final @Nullable Boolean keepItems) {
        service.delete(id, Boolean.TRUE.equals(keepItems));
        return null;
    }
}
