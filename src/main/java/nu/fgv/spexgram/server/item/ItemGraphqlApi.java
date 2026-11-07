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


package nu.fgv.spexgram.server.item;

import nu.fgv.spexgram.server.album.AlbumDtos.AlbumDto;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumRef;
import nu.fgv.spexgram.server.album.AlbumRepository;
import nu.fgv.spexgram.server.album.AlbumService;
import nu.fgv.spexgram.server.item.ItemDtos.ImmichJob;
import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.item.ItemDtos.ItemInput;
import nu.fgv.spexgram.server.item.ItemDtos.ItemsInput;
import nu.fgv.spexgram.server.item.ItemDtos.ItemsUpdateResult;
import nu.fgv.spexgram.server.item.ItemDtos.UnsortedDay;
import nu.fgv.spexgram.server.item.ItemDtos.UploadResult;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * GraphQL for items, uploads and the trash. A thin adapter: the services decide what the viewer may see and do.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ItemGraphqlApi {

    private final ItemService service;
    private final AlbumService albumService;
    private final AlbumRepository albumRepository;

    public ItemGraphqlApi(final ItemService service, final AlbumService albumService, final AlbumRepository albumRepository) {
        this.service = service;
        this.albumService = albumService;
        this.albumRepository = albumRepository;
    }

    @QueryMapping
    public @Nullable ItemDto item(@Argument final UUID id) {
        return service.item(id);
    }

    @QueryMapping
    public List<ItemDto> thisDayInHistory(@Argument final @Nullable Integer limit) {
        return service.thisDayInHistory(limit == null ? 30 : limit);
    }

    @QueryMapping
    public List<ItemDto> publicItems(@Argument final List<UUID> ids) {
        return service.publicItems(ids);
    }

    @QueryMapping
    public List<ItemDto> itemMedia(@Argument final List<UUID> ids) {
        return service.itemMedia(ids);
    }

    @QueryMapping
    public List<UnsortedDay> unsorted() {
        return service.unsorted();
    }

    @QueryMapping
    public Trash trash() {
        return new Trash(albumService.trashed(), service.trashed());
    }

    /**
     * The albums of items, as far as the viewer may see them; {@link ItemDto#albumId()} is already null otherwise.
     */
    @BatchMapping(typeName = "Item", field = "album")
    public Map<ItemDto, @Nullable AlbumRef> album(final List<ItemDto> items) {
        final Map<UUID, AlbumRef> albums = new HashMap<>();
        final Map<ItemDto, @Nullable AlbumRef> result = new LinkedHashMap<>();

        final List<UUID> ids = items.stream().map(ItemDto::albumId).filter(Objects::nonNull).distinct().toList();

        // One query for every album of the page of items, not one per album
        if (!ids.isEmpty()) {
            albumRepository.findByUuidIn(ids).forEach(album -> albums.put(album.getUuid(), new AlbumRef(album.getUuid(), album.getTitle(), album.getVisibility())));
        }
        items.forEach(item -> result.put(item, item.albumId() == null ? null : albums.get(item.albumId())));
        return result;
    }

    @MutationMapping
    public List<UploadResult> itemsAdd(@Argument final UUID albumId, @Argument final List<String> uploadIds) {
        return service.add(albumId, uploadIds);
    }

    @MutationMapping
    public ItemDto itemReplaceFile(@Argument final UUID id, @Argument final String uploadId) {
        return service.replaceFile(id, uploadId);
    }

    @MutationMapping
    public ItemDto itemUpdate(@Argument final UUID id, @Argument final ItemInput input) {
        return service.update(id, input);
    }

    @MutationMapping
    public ItemsUpdateResult itemsUpdate(@Argument final List<UUID> ids, @Argument final ItemsInput input) {
        return service.updateAll(ids, input);
    }

    @MutationMapping
    public List<ItemDto> itemsTrash(@Argument final List<UUID> ids) {
        return service.trash(ids);
    }

    @MutationMapping
    public List<ItemDto> itemsRestore(@Argument final List<UUID> ids) {
        return service.restore(ids);
    }

    @MutationMapping
    public @Nullable Void itemsDelete(@Argument final List<UUID> ids) {
        service.delete(ids);
        return null;
    }

    @MutationMapping
    public @Nullable Void trashEmpty() {
        service.emptyTrash();
        return null;
    }

    @MutationMapping
    public @Nullable Void immichJobRun(@Argument final List<UUID> itemIds, @Argument final ImmichJob job) {
        service.runImmichJob(itemIds, job);
        return null;
    }

    public record Trash(List<AlbumDto> albums, List<ItemDto> items) {
    }
}
