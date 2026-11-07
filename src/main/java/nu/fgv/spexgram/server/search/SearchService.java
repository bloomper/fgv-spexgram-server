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


package nu.fgv.spexgram.server.search;

import nu.fgv.spexgram.server.album.AlbumDtos.AlbumDto;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumFilter;
import nu.fgv.spexgram.server.album.AlbumService;
import nu.fgv.spexgram.server.immich.ImmichClient;
import nu.fgv.spexgram.server.item.Item;
import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.item.ItemDtos.MapItem;
import nu.fgv.spexgram.server.item.ItemRepository;
import nu.fgv.spexgram.server.item.ItemService;
import nu.fgv.spexgram.server.item.ItemSpecifications;
import nu.fgv.spexgram.server.item.ItemViews;
import nu.fgv.spexgram.server.media.MediaSigner;
import nu.fgv.spexgram.server.util.Locales;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.ViewerService;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Search (5.7 of the spec): by text across album titles, places and descriptions and item captions and photographers,
 * with MySQL's full-text indexes; by content through Immich's smart search, for members; and the map. Everything is
 * narrowed to what the viewer may see.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class SearchService {

    static final int MAX_LIMIT = 200;
    static final int CANDIDATES = 1000;
    static final int SMART_PAGE = 100;
    static final int SMART_PAGES = 5;
    static final int MAP_LIMIT = 5000;

    private final JdbcClient jdbcClient;
    private final AlbumService albumService;
    private final ItemService itemService;
    private final ItemRepository itemRepository;
    private final ItemViews views;
    private final ViewerService viewerService;
    private final ImmichClient immichClient;
    private final MediaSigner signer;

    @SuppressWarnings("java:S107")
    public SearchService(final JdbcClient jdbcClient, final AlbumService albumService, final ItemService itemService,
                         final ItemRepository itemRepository, final ItemViews views, final ViewerService viewerService,
                         final ImmichClient immichClient, final MediaSigner signer) {
        this.jdbcClient = jdbcClient;
        this.albumService = albumService;
        this.itemService = itemService;
        this.itemRepository = itemRepository;
        this.views = views;
        this.viewerService = viewerService;
        this.immichClient = immichClient;
        this.signer = signer;
    }

    /**
     * The words as a boolean full-text query: every word must match, as a prefix. Characters with a meaning in boolean
     * mode are dropped, and so are words too short for the index.
     */
    static @Nullable String booleanQuery(final String q) {
        final String query = Arrays.stream(q.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> word.length() >= 2)
                .limit(10)
                .map(word -> "+" + word + "*")
                .collect(Collectors.joining(" "));

        return query.isEmpty() ? null : query;
    }

    @Transactional(readOnly = true)
    public SearchResult search(final String q, final int limit) {
        final Viewer viewer = viewerService.current();
        final String query = booleanQuery(q);
        final int max = Math.clamp(limit, 1, MAX_LIMIT);

        if (query == null) {
            return new SearchResult(List.of(), List.of());
        }

        final List<Long> albums = jdbcClient.sql("""
                        SELECT id FROM album WHERE MATCH(title, place, description) AGAINST (:q IN BOOLEAN MODE)
                        ORDER BY MATCH(title, place, description) AGAINST (:q IN BOOLEAN MODE) DESC LIMIT :limit""")
                .param("q", query).param("limit", CANDIDATES).query(Long.class).list();
        final List<Long> items = jdbcClient.sql("""
                        SELECT id FROM item WHERE MATCH(caption, photographer) AGAINST (:q IN BOOLEAN MODE)
                        ORDER BY MATCH(caption, photographer) AGAINST (:q IN BOOLEAN MODE) DESC LIMIT :limit""")
                .param("q", query).param("limit", CANDIDATES).query(Long.class).list();

        return new SearchResult(albumService.visibleAmong(albums, viewer, max), itemService.visibleAmong(items, viewer, null, max));
    }

    /**
     * Items whose content matches the words, by Immich's search by content. Immich does not know who may see what, so
     * hits the viewer may not see are left out and further pages asked for until the limit is reached.
     */
    @Transactional(readOnly = true)
    public List<ItemDto> smartSearch(final String q, final @Nullable AlbumFilter filter, final int limit, final @Nullable Locale locale) {
        final Viewer viewer = viewerService.current();

        viewer.requireMember();
        if (q.isBlank()) {
            // Immich refuses an empty query, which would read as Immich being unavailable
            return List.of();
        }

        final int max = Math.clamp(limit, 1, MAX_LIMIT);
        final Set<Long> albums = filter == null ? null : albumService.albumIds(filter, viewer);
        final Specification<Item> inAlbums = albums == null ? null
                : (root, query, builder) -> albums.isEmpty() ? builder.disjunction() : root.get("album").get("id").in(albums);
        final List<ItemDto> result = new ArrayList<>();
        final Set<UUID> seen = new LinkedHashSet<>();

        for (int page = 1; page <= SMART_PAGES && result.size() < max; page++) {
            final ImmichClient.SmartPage hits = immichClient.smartSearch(q.strip(), Locales.supported(locale).getLanguage(), page, SMART_PAGE);
            final List<UUID> assets = hits.ids().stream().filter(seen::add).toList();
            final Map<UUID, Long> byAsset = itemRepository.findByImmichAssetIdIn(assets).stream()
                    .collect(Collectors.toMap(item -> Objects.requireNonNull(item.getImmichAssetId()), Item::getId));
            final List<Long> ids = assets.stream().map(byAsset::get).filter(Objects::nonNull).toList();

            result.addAll(itemService.visibleAmong(ids, viewer, inAlbums, max - result.size()));
            if (!hits.hasMore()) {
                break;
            }
        }
        return result;
    }

    /**
     * Items with a position the viewer may see, for the map; none for members when admins hide positions.
     */
    @Transactional(readOnly = true)
    public List<MapItem> mapItems(final @Nullable AlbumFilter filter) {
        final Viewer viewer = viewerService.current();

        viewer.requireMember();

        final ItemViews.Context context = views.context(viewer);

        if (!context.positions()) {
            return List.of();
        }

        Specification<Item> specification = ItemSpecifications.visibleTo(viewer)
                .and((root, query, builder) -> builder.and(builder.isNotNull(root.get("latitude")), builder.isNotNull(root.get("longitude"))));

        if (filter != null) {
            final Set<Long> albums = albumService.albumIds(filter, viewer);

            specification = specification.and((root, query, builder) -> albums.isEmpty() ? builder.disjunction() : root.get("album").get("id").in(albums));
        }

        final Instant expires = context.expires();

        return itemRepository.findAll(specification, PageRequest.of(0, MAP_LIMIT, Sort.by(Sort.Order.desc("takenAt"), Sort.Order.desc("id"))))
                .stream()
                .map(item -> new MapItem(item.getUuid(), Objects.requireNonNull(item.getLatitude()), Objects.requireNonNull(item.getLongitude()),
                        item.getTakenAt() == null ? null : item.getTakenAt().toString(),
                        item.getImmichAssetId() == null ? null : signer.sign(item.getImmichAssetId(), MediaSigner.Variant.THUMBNAIL, expires),
                        item.getImmichAssetId() == null ? null : expires))
                .toList();
    }

    public record SearchResult(List<AlbumDto> albums, List<ItemDto> items) {
    }
}
