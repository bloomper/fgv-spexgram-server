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

import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.album.AlbumDtos;
import nu.fgv.spexgram.server.album.AlbumKind;
import nu.fgv.spexgram.server.album.AlbumKindService;
import nu.fgv.spexgram.server.album.AlbumRepository;
import nu.fgv.spexgram.server.album.AlbumService;
import nu.fgv.spexgram.server.imports.ImportEngine.Batch;
import nu.fgv.spexgram.server.imports.ImportEngine.Handler;
import nu.fgv.spexgram.server.imports.ImportEngine.ItemResult;
import nu.fgv.spexgram.server.imports.ImportRecords.ImportAlbum;
import nu.fgv.spexgram.server.imports.ImportRecords.ImportItem;
import nu.fgv.spexgram.server.item.ItemDtos;
import nu.fgv.spexgram.server.item.ItemIntake;
import nu.fgv.spexgram.server.item.ItemRepository;
import nu.fgv.spexgram.server.item.ItemService;
import nu.fgv.spexgram.server.util.Uuids;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.visibility.ViewerService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spexgram's import API (spec 5.6.1): albums and their photos and videos, sent to Immich as uploads are. Albums and
 * items both arrive unpublished.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class SpexgramImport {

    public static final String ALBUM = "ALBUM";
    public static final String ITEM = "ITEM";

    private final ImportEngine engine;
    private final ImportFileStore files;
    private final AlbumService albums;
    private final AlbumRepository albumRepository;
    private final AlbumKindService kinds;
    private final ItemIntake intake;
    private final ItemService items;
    private final ItemRepository itemRepository;
    private final ViewerService viewers;

    @SuppressWarnings("java:S107")
    public SpexgramImport(final ImportEngine engine, final ImportFileStore files, final AlbumService albums,
                          final AlbumRepository albumRepository, final AlbumKindService kinds, final ItemIntake intake,
                          final ItemService items, final ItemRepository itemRepository, final ViewerService viewers) {
        this.engine = engine;
        this.files = files;
        this.albums = albums;
        this.albumRepository = albumRepository;
        this.kinds = kinds;
        this.intake = intake;
        this.items = items;
        this.itemRepository = itemRepository;
        this.viewers = viewers;
    }

    public List<ItemResult> run(final ImportRecords.Batch input, final boolean dryRun) {
        final int size = (input.albums() == null ? 0 : input.albums().size()) + (input.items() == null ? 0 : input.items().size());

        if (size > ImportEngine.MAX_RECORDS) {
            throw new BadRequestException("error.import.tooMany", ImportEngine.MAX_RECORDS);
        }

        final Batch batch = new Batch(dryRun, Boolean.TRUE.equals(input.createMissingKinds()));
        final List<ItemResult> results = new ArrayList<>();

        results.addAll(engine.run(albumHandler, input.albums(), batch));
        results.addAll(engine.run(itemHandler, input.items(), batch));
        return results;
    }

    public int forget(final String sourcePrefix) {
        return engine.forget(sourcePrefix, List.of(itemHandler, albumHandler));
    }

    private @Nullable UUID albumKind(final @Nullable String name, final Batch batch) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return kinds.match(name).map(AlbumKind::getUuid).orElseGet(() -> {
            if (batch.createMissingKinds()) {
                batch.warn("Ny albumsort: " + name.strip());
                return kinds.createForImport(name.strip()).getUuid();
            }
            throw new BadRequestException("error.kind.unknown", name,
                    kinds.kinds().stream().map(AlbumKindService.AlbumKindDto::nameSv).collect(Collectors.joining(", ")));
        });
    }

    private final Handler<ImportAlbum> albumHandler = new Handler<>() {

        @Override
        public String kind() {
            return ALBUM;
        }

        @Override
        public String sourceKey(final ImportAlbum record) {
            return record.sourceKey();
        }

        @Override
        public UUID apply(final ImportAlbum record, final @Nullable UUID existing, final Batch batch) {
            final AlbumDtos.AlbumInput input = new AlbumDtos.AlbumInput(record.spexIds(), record.title(), albumKind(record.kind(), batch),
                    record.dateFrom(), record.dateTo(), record.place(), record.description());

            return existing == null ? albums.create(input).id() : albums.update(existing, input).id();
        }

        @Override
        public @Nullable Long version(final UUID id) {
            return albums.recordVersion(id);
        }

        @Override
        public boolean forget(final UUID id) {
            return albums.forget(id);
        }
    };

    private final Handler<ImportItem> itemHandler = new Handler<>() {

        @Override
        public String kind() {
            return ITEM;
        }

        @Override
        public String sourceKey(final ImportItem record) {
            return record.sourceKey();
        }

        @Override
        public UUID apply(final ImportItem record, final @Nullable UUID existing, final Batch batch) {
            final java.nio.file.Path file = files.require(record.file().sha256());
            final UUID albumId = engine.recordOf(ALBUM, record.albumSourceKey());

            if (albumId == null) {
                if (batch.dryRun() && batch.planned(record.albumSourceKey())) {
                    return Uuids.v7();
                }
                throw new BadRequestException("error.field.invalid", "albumSourceKey");
            }
            if (batch.dryRun()) {
                return existing != null ? existing : Uuids.v7();
            }

            final Album album = albumRepository.findByUuid(albumId).orElseThrow(() -> new BadRequestException("error.field.invalid", "albumSourceKey"));
            final UUID id = existing != null && itemRepository.findByUuid(existing).isPresent() ? existing
                    : intake.importFile(album, file, Objects.requireNonNullElse(record.file().name(), "bild.jpg"), viewers.current());

            if (record.caption() != null || record.photographer() != null || record.takenAt() != null) {
                items.update(id, new ItemDtos.ItemInput(record.caption(), record.photographer(), record.takenAt(), null, null));
            }
            return id;
        }

        /** Immich's processing changes an item after the import, so only whether it is still there counts. */
        @Override
        public @Nullable Long version(final UUID id) {
            return itemRepository.findByUuid(id).isPresent() ? 0L : null;
        }

        @Override
        public boolean forget(final UUID id) {
            return albums.forget(id);
        }
    };
}
