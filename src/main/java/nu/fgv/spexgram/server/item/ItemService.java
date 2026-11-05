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

import jakarta.persistence.criteria.Expression;
import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.album.AlbumDtos.VisibilityCount;
import nu.fgv.spexgram.server.album.AlbumRepository;
import nu.fgv.spexgram.server.album.AlbumService;
import nu.fgv.spexgram.server.audit.AuditService;
import nu.fgv.spexgram.server.immich.ImmichClient;
import nu.fgv.spexgram.server.item.ItemDtos.ImmichJob;
import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.item.ItemDtos.ItemInput;
import nu.fgv.spexgram.server.item.ItemDtos.ItemPage;
import nu.fgv.spexgram.server.item.ItemDtos.ItemsInput;
import nu.fgv.spexgram.server.item.ItemDtos.UnsortedDay;
import nu.fgv.spexgram.server.item.ItemDtos.UploadResult;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.ViewerService;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Items: what the viewer may see of them, and editing, moving, publishing and trashing them for editors.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ItemService {

    static final int MAX_PAGE = 500;
    static final int MAX_IDS = 200;
    static final int MAX_BULK = 1000;
    private static final int MAX_TEXT = 255;
    private static final Sort BY_TAKEN = Sort.by(Sort.Order.asc("takenAt").nullsLast(), Sort.Order.asc("id"));

    private final ItemRepository repository;
    private final AlbumRepository albumRepository;
    private final AlbumService albumService;
    private final ViewerService viewerService;
    private final ItemViews views;
    private final AuditService auditService;
    private final ImmichClient immichClient;
    private final ItemIntake intake;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public ItemService(final ItemRepository repository, final AlbumRepository albumRepository,
                       final AlbumService albumService, final ViewerService viewerService, final ItemViews views, final AuditService auditService,
                       final ImmichClient immichClient, final ItemIntake intake, final Clock clock) {
        this.repository = repository;
        this.albumRepository = albumRepository;
        this.albumService = albumService;
        this.viewerService = viewerService;
        this.views = views;
        this.auditService = auditService;
        this.immichClient = immichClient;
        this.intake = intake;
        this.clock = clock;
    }

    private static Specification<Item> inAlbum(final Album album) {
        return (root, query, builder) -> builder.equal(root.get("album"), album);
    }

    static String describe(final Item item) {
        return StringUtils.hasText(item.getCaption()) ? "\"" + item.getCaption() + "\""
                : Objects.requireNonNullElse(item.getOriginalFileName(), item.getUuid().toString());
    }

    private static @Nullable String text(final @Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.strip().length() > MAX_TEXT) {
            throw new BadRequestException("error.item.tooLong", MAX_TEXT);
        }
        return value.strip();
    }

    private static @Nullable LocalDateTime taken(final @Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.strip());
        } catch (final DateTimeParseException e) {
            throw new BadRequestException("error.item.taken");
        }
    }

    private List<ItemDto> toDtos(final Collection<Item> items, final Viewer viewer) {
        final ItemViews.Context context = views.context(viewer);

        return items.stream().map(item -> views.toDto(item, context)).toList();
    }

    // ---------------------------------------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public @Nullable ItemDto item(final UUID id) {
        final Viewer viewer = viewerService.current();

        return repository.findByUuid(id).filter(item -> ItemViews.canSee(item, viewer)).map(item -> views.toDto(item, viewer)).orElse(null);
    }

    /**
     * The public items among the ids, as visitors see them, in the given order, for Spexipedia and other applications;
     * Spexipedia's service account also gets members' items.
     */
    @Transactional(readOnly = true)
    public List<ItemDto> publicItems(final List<UUID> ids) {
        final Viewer current = viewerService.currentForSpexipedia();

        return itemsByIds(ids, current.kind() == Viewer.Kind.SPEXIPEDIA ? current : Viewer.visitor());
    }

    /**
     * The items among the ids the viewer may see, in the given order, with fresh signed addresses: for a page that has
     * been open longer than its addresses last.
     */
    @Transactional(readOnly = true)
    public List<ItemDto> itemMedia(final List<UUID> ids) {
        return itemsByIds(ids, viewerService.current());
    }

    private List<ItemDto> itemsByIds(final List<UUID> ids, final Viewer viewer) {
        if (ids.size() > MAX_IDS) {
            throw new BadRequestException("error.item.tooMany", MAX_IDS);
        }

        final Map<UUID, Item> byId = repository.findByUuidIn(ids).stream().collect(Collectors.toMap(Item::getUuid, Function.identity()));

        return toDtos(ids.stream().distinct().map(byId::get).filter(Objects::nonNull)
                .filter(item -> ItemViews.canSee(item, viewer)).toList(), viewer);
    }

    /**
     * A page of an album's items the viewer may see, by when they were taken.
     */
    @Transactional(readOnly = true)
    public ItemPage albumItems(final UUID albumId, final int page, final int size) {
        final Viewer viewer = viewerService.current();
        final Album album = albumRepository.findByUuid(albumId).filter(found -> ItemViews.canSee(found, viewer))
                .orElseThrow(() -> new ResourceNotFoundException(Album.class, albumId));
        final int p = Math.max(page, 0);
        final int s = Math.clamp(size, 1, MAX_PAGE);
        // Editors open trashed albums to restore them, so their items are listed there too
        final Specification<Item> visible = viewer.isEditor() && album.isTrashed()
                ? (root, query, builder) -> builder.isNull(root.get("trashedAt"))
                : ItemSpecifications.visibleTo(viewer);
        final Page<Item> items = repository.findAll(visible.and(inAlbum(album)), PageRequest.of(p, s, BY_TAKEN));

        return new ItemPage(toDtos(items.getContent(), viewer), items.getTotalElements(), p, s);
    }

    /**
     * How many items of each album the viewer may see.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> itemCounts(final Collection<UUID> albumIds) {
        final Viewer viewer = viewerService.current();
        final Map<UUID, Integer> counts = new HashMap<>();

        for (final UUID albumId : albumIds) {
            albumRepository.findByUuid(albumId).ifPresent(album ->
                    counts.put(albumId, (int) repository.count(ItemSpecifications.visibleTo(viewer).and(inAlbum(album)))));
        }
        return counts;
    }

    /**
     * The cover of an album: the chosen one if the viewer may see it, else the first item they may see.
     */
    @Transactional(readOnly = true)
    public @Nullable ItemDto cover(final UUID albumId) {
        final Viewer viewer = viewerService.current();
        final Album album = albumRepository.findByUuid(albumId).orElse(null);

        if (album == null) {
            return null;
        }

        final Specification<Item> visible = ItemSpecifications.visibleTo(viewer).and(inAlbum(album));
        final Item chosen = album.getCoverItemId() == null ? null : repository.findById(album.getCoverItemId())
                .filter(item -> ItemViews.canSee(item, viewer) && !item.isTrashed() && item.getStatus() == ItemStatus.READY)
                .orElse(null);
        final Item cover = chosen != null ? chosen : repository.findAll(visible, PageRequest.of(0, 1, BY_TAKEN)).stream().findFirst().orElse(null);

        return cover == null ? null : views.toDto(cover, viewer);
    }

    /**
     * How many items outside the trash of the album have each visibility of their own, for editors.
     */
    @Transactional(readOnly = true)
    public List<VisibilityCount> visibilityCounts(final UUID albumId) {
        viewerService.current().requireEditor();

        final Album album = albumRepository.findByUuid(albumId).orElseThrow(() -> new ResourceNotFoundException(Album.class, albumId));
        final Map<Visibility, Integer> counts = new EnumMap<>(Visibility.class);

        for (final Visibility visibility : Visibility.values()) {
            counts.put(visibility, 0);
        }
        repository.findByAlbumAndTrashedAtIsNull(album).forEach(item -> counts.merge(item.getVisibility(), 1, Integer::sum));
        return counts.entrySet().stream().map(entry -> new VisibilityCount(entry.getKey(), entry.getValue())).toList();
    }

    /**
     * Items taken on today's date in earlier years that the viewer may see, newest first.
     */
    @Transactional(readOnly = true)
    public List<ItemDto> thisDayInHistory(final int limit) {
        final Viewer viewer = viewerService.current();
        final LocalDate today = LocalDate.now(clock);
        final Specification<Item> onThisDay = (root, query, builder) -> {
            final Expression<LocalDateTime> taken = root.get("takenAt");

            return builder.and(
                    builder.equal(builder.function("MONTH", Integer.class, taken), today.getMonthValue()),
                    builder.equal(builder.function("DAY", Integer.class, taken), today.getDayOfMonth()),
                    builder.lessThan(taken, today.withDayOfYear(1).atStartOfDay()));
        };

        return toDtos(repository.findAll(ItemSpecifications.visibleTo(viewer).and(onThisDay),
                PageRequest.of(0, Math.clamp(limit, 1, MAX_IDS), Sort.by(Sort.Order.desc("takenAt"), Sort.Order.desc("id")))).getContent(), viewer);
    }

    /**
     * Items visible to the viewer among the given ones, in that order, for search.
     */
    @Transactional(readOnly = true)
    public List<ItemDto> visibleAmong(final List<Long> ids, final Viewer viewer, final @Nullable Specification<Item> filter, final int limit) {
        if (ids.isEmpty()) {
            return List.of();
        }

        Specification<Item> specification = ItemSpecifications.visibleTo(viewer).and((root, query, builder) -> root.get("id").in(ids));

        if (filter != null) {
            specification = specification.and(filter);
        }

        final Map<Long, Item> found = repository.findAll(specification).stream().collect(Collectors.toMap(Item::getId, Function.identity()));

        return toDtos(ids.stream().map(found::get).filter(Objects::nonNull).limit(limit).toList(), viewer);
    }

    @Transactional(readOnly = true)
    public List<UnsortedDay> unsorted() {
        final Viewer viewer = viewerService.current();

        viewer.requireEditor();

        final Map<@Nullable LocalDate, List<Item>> days = new LinkedHashMap<>();

        for (final Item item : repository.findByAlbumIsNullAndTrashedAtIsNullOrderByTakenAtAscIdAsc()) {
            days.computeIfAbsent(item.getTakenAt() == null ? null : item.getTakenAt().toLocalDate(), _ -> new ArrayList<>()).add(item);
        }

        final ItemViews.Context context = views.context(viewer);
        final List<UnsortedDay> result = new ArrayList<>();

        days.forEach((day, items) -> result.add(new UnsortedDay(day == null ? null : day.toString(),
                items.stream().map(item -> views.toDto(item, context)).toList())));
        return result;
    }

    @Transactional(readOnly = true)
    public List<ItemDto> trashed() {
        final Viewer viewer = viewerService.current();

        viewer.requireEditor();
        return toDtos(repository.findByTrashedAtIsNotNullOrderByTrashedAtDesc(), viewer);
    }

    @Transactional(readOnly = true)
    public List<ItemDto> failed() {
        final Viewer viewer = viewerService.current();

        viewer.requireAdmin();
        return toDtos(repository.findByStatus(ItemStatus.FAILED), viewer);
    }

    // ---------------------------------------------------------------------------------------------- editing

    private List<Item> editable(final Collection<UUID> ids) {
        if (ids.size() > MAX_BULK) {
            throw new BadRequestException("error.item.tooMany", MAX_BULK);
        }

        final List<Item> items = repository.findByUuidIn(ids);

        if (items.size() != new HashSet<>(ids).size()) {
            throw new ResourceNotFoundException("error.item.notFound", ids);
        }
        return items;
    }

    /**
     * Sends finished uploads to Immich as members' items of the album.
     */
    public List<UploadResult> add(final UUID albumId, final List<String> uploadIds) {
        final Viewer viewer = viewerService.current();

        viewer.requireEditor();

        final Album album = albumRepository.findByUuid(albumId).orElseThrow(() -> new ResourceNotFoundException(Album.class, albumId));

        return intake.add(album, uploadIds, viewer, false);
    }

    public ItemDto replaceFile(final UUID id, final String uploadId) {
        return intake.replaceFile(id, uploadId, viewerService.current());
    }

    @Transactional
    public ItemDto update(final UUID id, final ItemInput input) {
        final Viewer viewer = viewerService.current();

        viewer.requireEditor();

        final Item item = editable(List.of(id)).getFirst();

        if ((input.latitude() == null) != (input.longitude() == null)
                || (input.latitude() != null && (Math.abs(input.latitude()) > 90 || Math.abs(input.longitude()) > 180))) {
            throw new BadRequestException("error.item.position");
        }
        item.setCaption(StringUtils.hasText(input.caption()) ? input.caption().strip() : null);
        item.setPhotographer(text(input.photographer()));
        item.setTakenAt(taken(input.takenAt()));
        item.setLatitude(input.latitude());
        item.setLongitude(input.longitude());
        auditService.record("UPDATE", "ITEM", item.getUuid(), "Ändrade " + describe(item));
        return views.toDto(item, viewer);
    }

    @Transactional
    public List<ItemDto> updateAll(final List<UUID> ids, final ItemsInput input) {
        final Viewer viewer = viewerService.current();

        viewer.requireEditor();

        final List<Item> items = editable(ids);
        final Album album = input.albumId() == null ? null : albumRepository.findByUuid(input.albumId())
                .orElseThrow(() -> new ResourceNotFoundException(Album.class, input.albumId()));

        for (final Item item : items) {
            if (input.caption() != null) {
                item.setCaption(StringUtils.hasText(input.caption()) ? input.caption().strip() : null);
            }
            if (input.photographer() != null) {
                item.setPhotographer(text(input.photographer()));
            }
            if (input.visibility() != null) {
                item.setVisibility(input.visibility());
            }
            if (album != null) {
                item.setAlbum(album);
            }
        }

        final List<String> changes = new ArrayList<>();

        if (input.caption() != null || input.photographer() != null) {
            changes.add("ändrade bildtext eller fotograf");
        }
        if (input.visibility() != null) {
            changes.add("gjorde dem " + switch (input.visibility()) {
                case UNPUBLISHED -> "opublicerade";
                case MEMBERS -> "synliga för medlemmar";
                case PUBLIC -> "publika";
            });
        }
        if (album != null) {
            changes.add("flyttade dem till albumet " + album.getTitle());
        }
        for (final Item item : items) {
            auditService.record(input.visibility() != null ? "VISIBILITY" : "UPDATE", "ITEM", item.getUuid(),
                    "%s: %s".formatted(describe(item), String.join(", ", changes)));
        }
        return toDtos(items, viewer);
    }

    @Transactional
    public List<ItemDto> trash(final List<UUID> ids) {
        final Viewer viewer = viewerService.current();

        viewer.requireEditor();

        final List<Item> items = editable(ids);

        for (final Item item : items) {
            if (!item.isTrashed()) {
                item.setTrashedAt(clock.instant());
                auditService.record("TRASH", "ITEM", item.getUuid(), "Lade %s i papperskorgen".formatted(describe(item)));
            }
        }
        return toDtos(items, viewer);
    }

    @Transactional
    public List<ItemDto> restore(final List<UUID> ids) {
        final Viewer viewer = viewerService.current();

        viewer.requireEditor();

        final List<Item> items = editable(ids);

        for (final Item item : items) {
            if (item.isTrashed()) {
                item.setTrashedAt(null);
                auditService.record("RESTORE", "ITEM", item.getUuid(), "Återställde " + describe(item));
            }
        }
        return toDtos(items, viewer);
    }

    /**
     * Deletes trashed items with their files.
     */
    @Transactional
    public void delete(final List<UUID> ids) {
        viewerService.current().requireAdmin();

        final List<Item> items = editable(ids);

        if (items.stream().anyMatch(item -> !item.isTrashed())) {
            throw new BadRequestException("error.item.notTrashed");
        }
        deleteWithFiles(items);
        items.forEach(item -> auditService.record("DELETE", "ITEM", item.getUuid(), "Raderade " + describe(item)));
    }

    /**
     * Deletes items and their files in Immich, clearing them as covers.
     */
    public void deleteWithFiles(final List<Item> items) {
        immichClient.delete(items.stream().map(Item::getImmichAssetId).filter(Objects::nonNull).toList());
        for (final Item item : items) {
            if (item.getAlbum() != null && item.getId().equals(item.getAlbum().getCoverItemId())) {
                item.getAlbum().setCoverItemId(null);
            }
        }
        repository.deleteAll(items);
    }

    /**
     * Deletes every trashed album and item with their files.
     */
    @Transactional
    public void emptyTrash() {
        viewerService.current().requireAdmin();

        final List<Item> items = repository.findByTrashedAtIsNotNullOrderByTrashedAtDesc();
        final List<Album> albums = albumService.trashedAlbums();

        deleteWithFiles(items);
        albums.forEach(album -> albumService.deleteWithItems(album, false));
        auditService.record("DELETE", "TRASH", null, "Tömde papperskorgen: %d album och %d bilder och filmer".formatted(albums.size(), items.size()));
    }

    @Transactional(readOnly = true)
    public void runImmichJob(final List<UUID> ids, final ImmichJob job) {
        viewerService.current().requireAdmin();
        immichClient.runAssetJob(editable(ids).stream().map(Item::getImmichAssetId).filter(Objects::nonNull).toList(), job.immichName());
    }
}
