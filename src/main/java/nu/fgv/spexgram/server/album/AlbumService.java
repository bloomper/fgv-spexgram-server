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

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumDto;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumFilter;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumInput;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumPage;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumSpexDto;
import nu.fgv.spexgram.server.album.AlbumDtos.CategoryDto;
import nu.fgv.spexgram.server.audit.AuditService;
import nu.fgv.spexgram.server.immich.ImmichClient;
import nu.fgv.spexgram.server.item.ContributionStatus;
import nu.fgv.spexgram.server.item.Item;
import nu.fgv.spexgram.server.item.ItemRepository;
import nu.fgv.spexgram.server.item.ItemSpecifications;
import nu.fgv.spexgram.server.item.ItemViews;
import nu.fgv.spexgram.server.spex.SpexDto;
import nu.fgv.spexgram.server.spex.SpexService;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Albums: browsing what the viewer may see, and creating, editing, publishing and trashing them for editors.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class AlbumService {

    static final int MAX_SPEX = 10;
    static final int MAX_PAGE = 200;
    private static final int MAX_TEXT = 255;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("dateFrom").nullsLast(), Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final AlbumRepository repository;
    private final ItemRepository itemRepository;
    private final AlbumKindService kindService;
    private final SpexService spexService;
    private final ViewerService viewerService;
    private final AuditService auditService;
    private final ImmichClient immichClient;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public AlbumService(final AlbumRepository repository, final ItemRepository itemRepository,
                        final AlbumKindService kindService, final SpexService spexService,
                        final ViewerService viewerService, final AuditService auditService,
                        final ImmichClient immichClient, final Clock clock) {
        this.repository = repository;
        this.itemRepository = itemRepository;
        this.kindService = kindService;
        this.spexService = spexService;
        this.viewerService = viewerService;
        this.auditService = auditService;
        this.immichClient = immichClient;
        this.clock = clock;
    }

    public static AlbumDto toDto(final Album album) {
        return new AlbumDto(album.getUuid(), album.getTitle(), AlbumKindService.toDto(album.getKind()),
                album.getSpex().stream().map(ref -> AlbumSpexDto.of(ref, null)).toList(),
                album.getDateFrom() == null ? null : album.getDateFrom().toString(),
                album.getDateTo() == null ? null : album.getDateTo().toString(),
                album.getPlace(), album.getDescription(), album.getVisibility(), album.getSuggestedBy() != null,
                album.getCoverItemId(), album.getTrashedAt(), Objects.requireNonNull(album.getCreatedAt()),
                album.getLastModifiedAt());
    }

    static @Nullable String text(final @Nullable String value, final int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.strip().length() > max) {
            throw new BadRequestException("error.album.tooLong", max);
        }
        return value.strip();
    }

    public static @Nullable LocalDate date(final @Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (final DateTimeParseException e) {
            throw new BadRequestException("error.album.date");
        }
    }

    private static Specification<Album> filtered(final @Nullable AlbumFilter filter, final Viewer viewer) {
        final Specification<Album> visible = ItemSpecifications.albumsVisibleTo(viewer);

        if (filter == null) {
            return visible;
        }
        return visible.and((root, query, builder) -> {
            final List<Predicate> predicates = new ArrayList<>();

            if (filter.kindId() != null) {
                predicates.add(builder.equal(root.get("kind").get("uuid"), filter.kindId()));
            }
            if (filter.visibility() != null && viewer.isEditor()) {
                predicates.add(builder.equal(root.get("visibility"), filter.visibility()));
            }
            if (filter.categoryId() != null || filter.spexId() != null || filter.year() != null) {
                final Join<Album, SpexRef> spex = root.join("spex", JoinType.LEFT);

                Objects.requireNonNull(query).distinct(true);
                if (filter.categoryId() != null) {
                    predicates.add(builder.equal(spex.get("categoryId"), filter.categoryId()));
                }
                if (filter.spexId() != null) {
                    predicates.add(builder.or(builder.equal(spex.get("id"), filter.spexId()), builder.equal(spex.get("parentId"), filter.spexId())));
                }
                if (filter.year() != null) {
                    predicates.add(builder.or(builder.equal(spex.get("year"), filter.year()),
                            builder.between(root.get("dateFrom"), LocalDate.of(filter.year(), 1, 1), LocalDate.of(filter.year(), 12, 31))));
                }
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        });
    }

    /**
     * The year an album is listed under: its main spex's, else its date's.
     */
    static @Nullable Integer year(final Album album) {
        final SpexRef main = album.mainSpex();

        if (main != null && main.getYear() != null) {
            return main.getYear();
        }
        return album.getDateFrom() == null ? null : album.getDateFrom().getYear();
    }

    // ---------------------------------------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public AlbumPage albums(final @Nullable AlbumFilter filter, final int page, final int size) {
        final Viewer viewer = viewerService.current();
        final int p = Math.max(page, 0);
        final int s = Math.clamp(size, 1, MAX_PAGE);
        final Page<Album> albums = repository.findAll(filtered(filter, viewer), PageRequest.of(p, s, NEWEST_FIRST));

        return new AlbumPage(albums.getContent().stream().map(AlbumService::toDto).toList(), albums.getTotalElements(), p, s);
    }

    @Transactional(readOnly = true)
    public @Nullable AlbumDto album(final UUID id) {
        final Viewer viewer = viewerService.current();

        return repository.findByUuid(id).filter(album -> ItemViews.canSee(album, viewer)).map(AlbumService::toDto).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<AlbumDto> recent(final int limit) {
        final Viewer viewer = viewerService.current();

        return repository.findAll(ItemSpecifications.albumsVisibleTo(viewer),
                        PageRequest.of(0, Math.clamp(limit, 1, MAX_PAGE), Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))))
                .stream().map(AlbumService::toDto).toList();
    }

    /**
     * The albums of a spex and its revivals, oldest first, for spex pages here and in Spexipedia.
     */
    @Transactional(readOnly = true)
    public List<AlbumDto> spexAlbums(final UUID spexId) {
        final Viewer viewer = viewerService.currentForSpexipedia();

        return repository.findAll(filtered(new AlbumFilter(null, null, spexId, null, null), viewer)).stream()
                .sorted(Comparator.comparing((Album album) -> album.getDateFrom() == null ? LocalDate.MAX : album.getDateFrom())
                        .thenComparing(Album::getId))
                .map(AlbumService::toDto)
                .toList();
    }

    /**
     * The ids of the albums the viewer may see that match the filter, for narrowing searches of items.
     */
    @Transactional(readOnly = true)
    public Set<Long> albumIds(final @Nullable AlbumFilter filter, final Viewer viewer) {
        return repository.findAll(filtered(filter, viewer)).stream().map(Album::getId).collect(Collectors.toSet());
    }

    /**
     * The albums among the ids the viewer may see, in that order, for search.
     */
    @Transactional(readOnly = true)
    public List<AlbumDto> visibleAmong(final List<Long> ids, final Viewer viewer, final int limit) {
        if (ids.isEmpty()) {
            return List.of();
        }

        final Map<Long, Album> found = repository.findAll(ItemSpecifications.albumsVisibleTo(viewer)
                        .and((root, query, builder) -> root.get("id").in(ids))).stream()
                .collect(Collectors.toMap(Album::getId, album -> album));

        return ids.stream().map(found::get).filter(Objects::nonNull).limit(limit).map(AlbumService::toDto).toList();
    }

    private List<Album> visibleAlbums() {
        return repository.findAll(ItemSpecifications.albumsVisibleTo(viewerService.current()));
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> categories() {
        final Map<UUID, String> names = new LinkedHashMap<>();
        final Map<UUID, Set<Long>> albums = new LinkedHashMap<>();

        for (final Album album : visibleAlbums()) {
            for (final SpexRef ref : album.getSpex()) {
                if (ref.getCategoryId() != null) {
                    names.putIfAbsent(ref.getCategoryId(), Objects.requireNonNullElse(ref.getCategoryName(), ""));
                    albums.computeIfAbsent(ref.getCategoryId(), _ -> new HashSet<>()).add(album.getId());
                }
            }
        }
        return names.entrySet().stream()
                .map(entry -> new CategoryDto(entry.getKey(), entry.getValue(), albums.get(entry.getKey()).size()))
                .sorted(Comparator.comparing(CategoryDto::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Integer> years() {
        final Set<Integer> years = new TreeSet<>(Comparator.reverseOrder());

        visibleAlbums().stream().map(AlbumService::year).filter(Objects::nonNull).forEach(years::add);
        return List.copyOf(years);
    }

    /**
     * Every spex with an album the viewer may see, newest first, with how many.
     */
    @Transactional(readOnly = true)
    public List<AlbumSpexDto> spexWithAlbums() {
        final Map<UUID, SpexRef> spex = new LinkedHashMap<>();
        final Map<UUID, Integer> counts = new LinkedHashMap<>();

        for (final Album album : visibleAlbums()) {
            album.getSpex().stream().map(SpexRef::getId).distinct().forEach(id -> counts.merge(id, 1, Integer::sum));
            album.getSpex().forEach(ref -> spex.putIfAbsent(ref.getId(), ref));
        }
        return spex.values().stream()
                .sorted(Comparator.comparing((SpexRef ref) -> ref.getYear() == null ? 0 : ref.getYear()).reversed()
                        .thenComparing(ref -> Objects.requireNonNullElse(ref.getTitle(), "")))
                .map(ref -> AlbumSpexDto.of(ref, counts.get(ref.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AlbumDto> trashed() {
        viewerService.current().requireEditor();
        return repository.findByTrashedAtIsNotNullOrderByTrashedAtDesc().stream().map(AlbumService::toDto).toList();
    }

    /**
     * The album for an editor's change, or not found.
     */
    public Album editable(final UUID id) {
        viewerService.current().requireEditor();
        return repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException(Album.class, id));
    }

    // ---------------------------------------------------------------------------------------------- editing

    @Transactional
    public AlbumDto create(final AlbumInput input) {
        viewerService.current().requireEditor();

        final Album album = new Album();

        apply(album, input, kindService.kindOrDefault(input.kindId()));
        repository.save(album);
        auditService.record("CREATE", "ALBUM", album.getUuid(), "Skapade albumet " + album.getTitle());
        return toDto(album);
    }

    @Transactional
    public AlbumDto update(final UUID id, final AlbumInput input) {
        final Album album = editable(id);

        apply(album, input, input.kindId() == null ? album.getKind() : kindService.kindOrDefault(input.kindId()));
        auditService.record("UPDATE", "ALBUM", album.getUuid(), "Ändrade albumet " + album.getTitle());
        return toDto(album);
    }

    /**
     * Fills an album from the input; the title defaults to the main spex's title and year, plus the kind.
     */
    public void apply(final Album album, final AlbumInput input, final AlbumKind kind) {
        final List<UUID> spexIds = input.spexIds() == null ? List.of() : input.spexIds().stream().distinct().toList();

        if (spexIds.size() > MAX_SPEX) {
            throw new BadRequestException("error.album.tooManySpex", MAX_SPEX);
        }

        final List<SpexRef> refs = new ArrayList<>();
        final Map<UUID, SpexRef> current = album.getSpex().stream().collect(Collectors.toMap(SpexRef::getId, ref -> ref, (a, _) -> a));

        for (final UUID spexId : spexIds) {
            // Spex the album already has keep their copies; only new ones are read from Spexregister
            final SpexRef ref = current.containsKey(spexId) ? current.get(spexId) : copyOf(spexService.byId(spexId));

            refs.add(ref);
        }

        final String title = text(input.title(), MAX_TEXT);
        final LocalDate from = date(input.dateFrom());
        final LocalDate to = date(input.dateTo());

        if (title == null && refs.isEmpty()) {
            throw new BadRequestException("error.album.titleRequired");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new BadRequestException("error.album.date");
        }
        album.getSpex().clear();
        album.getSpex().addAll(refs);
        album.setKind(kind);
        album.setTitle(title != null ? title : defaultTitle(refs.getFirst(), kind));
        album.setDateFrom(from);
        album.setDateTo(to == null || to.equals(from) ? null : to);
        album.setPlace(text(input.place(), MAX_TEXT));
        album.setDescription(StringUtils.hasText(input.description()) ? input.description().strip() : null);
    }

    private SpexRef copyOf(final SpexDto spex) {
        final SpexRef ref = new SpexRef();

        spexService.copy(spex, ref);
        return ref;
    }

    static String defaultTitle(final SpexRef spex, final AlbumKind kind) {
        final String title = Objects.requireNonNullElse(spex.getTitle(), "");

        return (spex.getYear() == null ? title : title + " " + spex.getYear()) + " – " + kind.getNameSv().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public AlbumDto setVisibility(final UUID id, final Visibility visibility, final boolean items) {
        final Album album = editable(id);

        album.setVisibility(visibility);
        if (visibility != Visibility.UNPUBLISHED) {
            // Publishing ends a suggestion's waiting
            album.setSuggestedBy(null);
        }
        if (items) {
            itemRepository.findByAlbumAndTrashedAtIsNull(album).stream()
                    .filter(item -> item.getContributionStatus() != ContributionStatus.WAITING)
                    .forEach(item -> item.setVisibility(visibility));
        }
        auditService.record("VISIBILITY", "ALBUM", album.getUuid(), "Gjorde albumet %s %s%s".formatted(album.getTitle(),
                describe(visibility), items ? ", med alla bilder och filmer" : ""));
        return toDto(album);
    }

    public static String describe(final Visibility visibility) {
        return switch (visibility) {
            case UNPUBLISHED -> "opublicerat";
            case MEMBERS -> "synligt för medlemmar";
            case PUBLIC -> "publikt";
        };
    }

    @Transactional
    public AlbumDto setCover(final UUID id, final @Nullable UUID itemId) {
        final Album album = editable(id);

        if (itemId == null) {
            album.setCoverItemId(null);
        } else {
            final Item item = itemRepository.findByUuid(itemId)
                    .filter(found -> found.getAlbum() != null && found.getAlbum().getId().equals(album.getId()))
                    .orElseThrow(() -> new BadRequestException("error.album.cover"));

            album.setCoverItemId(item.getId());
        }
        auditService.record("UPDATE", "ALBUM", album.getUuid(), "Valde omslag för albumet " + album.getTitle());
        return toDto(album);
    }

    @Transactional
    public AlbumDto trash(final UUID id) {
        final Album album = editable(id);

        if (!album.isTrashed()) {
            album.setTrashedAt(clock.instant());
            auditService.record("TRASH", "ALBUM", album.getUuid(), "Lade albumet %s i papperskorgen".formatted(album.getTitle()));
        }
        return toDto(album);
    }

    @Transactional
    public AlbumDto restore(final UUID id) {
        final Album album = editable(id);

        if (album.isTrashed()) {
            album.setTrashedAt(null);
            auditService.record("RESTORE", "ALBUM", album.getUuid(), "Återställde albumet " + album.getTitle());
        }
        return toDto(album);
    }

    /**
     * Deletes a trashed album with its items and their files, or leaves its items unsorted.
     */
    @Transactional
    public void delete(final UUID id, final boolean keepItems) {
        viewerService.current().requireAdmin();

        final Album album = repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException(Album.class, id));

        if (!album.isTrashed()) {
            throw new BadRequestException("error.album.notTrashed");
        }
        deleteWithItems(album, keepItems);
        auditService.record("DELETE", "ALBUM", album.getUuid(), "Raderade albumet %s%s".formatted(album.getTitle(),
                keepItems ? " men behöll bilderna och filmerna som osorterade" : " med alla bilder och filmer"));
    }

    /**
     * Deletes an album; its items are deleted with their files in Immich, or become unsorted.
     */
    public void deleteWithItems(final Album album, final boolean keepItems) {
        final List<Item> items = itemRepository.findByAlbum(album);

        if (keepItems) {
            items.forEach(item -> {
                item.setAlbum(null);
                item.setTrashedAt(null);
                item.setVisibility(Visibility.UNPUBLISHED);
            });
        } else {
            immichClient.delete(items.stream().map(Item::getImmichAssetId).filter(Objects::nonNull).toList());
            itemRepository.deleteAll(items);
        }
        album.setCoverItemId(null);
        repository.delete(album);
    }

    /**
     * Every album in the trash, for emptying it.
     */
    public List<Album> trashedAlbums() {
        return repository.findByTrashedAtIsNotNullOrderByTrashedAtDesc();
    }
}
