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

package nu.fgv.spexgram.server.mcp;

import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumDto;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumFilter;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumPage;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumSpexDto;
import nu.fgv.spexgram.server.album.AlbumService;
import nu.fgv.spexgram.server.item.ItemService;
import nu.fgv.spexgram.server.search.SearchService;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Public albums over MCP: listing, searching and reading an album's description, and the spex they are about. Every
 * tool reads as a visitor, through the same services and visibility rules as the API without login, so an editor gets
 * no more here than anyone else. The items themselves, their people, and anything that changes are left out.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class AlbumMcpTools {

    /**
     * The largest page, as for visitors in the API.
     */
    static final int MAX_PAGE = 50;

    private final AlbumService albums;
    private final ItemService items;
    private final SearchService search;
    private final McpToolSupport support;

    public AlbumMcpTools(final AlbumService albums, final ItemService items, final SearchService search, final McpToolSupport support) {
        this.albums = albums;
        this.items = items;
        this.search = search;
        this.support = support;
    }

    private static Spex toMcp(final AlbumSpexDto spex) {
        return new Spex(spex.id(), spex.title(), spex.year(), Boolean.TRUE.equals(spex.revival()), spex.parentId(), spex.categoryName(),
                spex.albumCount());
    }

    /**
     * The year an album is listed under: its main spex's, else its date's, as in the web.
     */
    private static @Nullable Integer year(final AlbumDto album) {
        if (!album.spex().isEmpty() && album.spex().getFirst().year() != null) {
            return album.spex().getFirst().year();
        }
        return album.dateFrom() == null ? null : Integer.valueOf(album.dateFrom().substring(0, 4));
    }

    private static int size(final @Nullable Integer size) {
        return size == null ? MAX_PAGE : Math.clamp(size, 1, MAX_PAGE);
    }

    private List<AlbumSummary> summaries(final List<AlbumDto> page) {
        final Map<UUID, Integer> counts = items.itemCounts(page.stream().map(AlbumDto::id).toList(), McpToolSupport.VIEWER);

        return page.stream()
                .map(album -> new AlbumSummary(album.id(), album.title(), album.spex().stream().map(AlbumMcpTools::toMcp).toList(), year(album),
                        album.dateFrom(), album.dateTo(), counts.getOrDefault(album.id(), 0), support.link("/albums/" + album.id())))
                .toList();
    }

    /**
     * The filter for a spex (an id, or a title among the spex with public albums) and a year. A title matching several
     * spex is refused with them; one matching none gives an empty result, as no public album is about it.
     */
    private Optional<AlbumFilter> filter(final @Nullable String spex, final @Nullable Integer year) {
        if (!StringUtils.hasText(spex)) {
            return Optional.of(new AlbumFilter(null, year, null, null, null));
        }

        final String wanted = spex.strip();

        if (wanted.matches("\\p{XDigit}{8}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{4}-\\p{XDigit}{12}")) {
            return Optional.of(new AlbumFilter(null, year, McpToolSupport.id(wanted), null, null));
        }

        final String lower = wanted.toLowerCase(Locale.ROOT);
        final List<AlbumSpexDto> known = albums.spexWithAlbums(McpToolSupport.VIEWER);
        final List<AlbumSpexDto> exact = known.stream().filter(s -> s.title() != null && s.title().toLowerCase(Locale.ROOT).equals(lower)).toList();
        final List<AlbumSpexDto> matches = !exact.isEmpty() ? exact
                : known.stream().filter(s -> s.title() != null && s.title().toLowerCase(Locale.ROOT).contains(lower)).toList();
        // A spex and its revivals are one family: filtering by the original finds the revivals' albums too
        final Map<UUID, String> families = matches.stream()
                .collect(Collectors.toMap(AlbumSpexDto::familyId, s -> Objects.requireNonNullElse(s.title(), ""), (a, _) -> a));

        if (families.isEmpty()) {
            return Optional.empty();
        }
        if (families.size() > 1) {
            throw new BadRequestException("error.mcp.spexAmbiguous", wanted, String.join(", ", families.values().stream().sorted().toList()));
        }
        return Optional.of(new AlbumFilter(null, year, families.keySet().iterator().next(), null, null));
    }

    @McpTool(name = "list_albums",
            description = "List Spexgram's public albums, newest first, optionally of a spex (with its revivals) and a year. Each row has the album's id, title, spex, year, dates, how many public photos and videos it has, and a link to its page. Only public albums are listed, whoever is signed in.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public AlbumList listAlbums(
            @McpToolParam(required = false, description = "A spex: its id from list_spex, or its title, e.g. Cleopatra") final @Nullable String spex,
            @McpToolParam(required = false, description = "A year, e.g. 2024: the spex's year or the album's date") final @Nullable Integer year,
            @McpToolParam(required = false, description = "The page, counting from 0") final @Nullable Integer page,
            @McpToolParam(required = false, description = "Albums per page, at most 50 (the default)") final @Nullable Integer size) {
        return support.call(() -> {
            final int p = page == null ? 0 : Math.max(page, 0);
            final int s = size(size);
            final Optional<AlbumFilter> filter = filter(spex, year);

            if (filter.isEmpty()) {
                return new AlbumList(List.of(), 0, p, s);
            }

            final AlbumPage found = albums.albums(filter.get(), p, s, McpToolSupport.VIEWER);

            return new AlbumList(summaries(found.albums()), found.totalCount(), found.page(), found.size());
        });
    }

    @McpTool(name = "search_albums",
            description = "Search Spexgram's public albums by words in their titles, places and descriptions, best match first, optionally within a spex and a year. Every word must match, as the start of a word. Only public albums are searched, whoever is signed in.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<AlbumSummary> searchAlbums(
            @McpToolParam(description = "The words, e.g. kören premiär") final String query,
            @McpToolParam(required = false, description = "A spex: its id from list_spex, or its title") final @Nullable String spex,
            @McpToolParam(required = false, description = "A year, e.g. 2024") final @Nullable Integer year,
            @McpToolParam(required = false, description = "At most this many, up to 50 (the default)") final @Nullable Integer limit) {
        return support.call(() -> new McpToolSupport.ListResult<>(filter(spex, year)
                .map(filter -> summaries(search.albums(query, filter, McpToolSupport.VIEWER, size(limit))))
                .orElse(List.of())));
    }

    @McpTool(name = "get_album",
            description = "A public album: its title, kind, spex, year, dates, place, description, how many public photos and videos it has, and a link to its page in Spexgram. An album that is not public is not found.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public AlbumDetail getAlbum(@McpToolParam(description = "The album's id") final String albumId) {
        return support.call(() -> {
            final UUID id = McpToolSupport.id(albumId);
            final AlbumDto album = albums.album(id, McpToolSupport.VIEWER);

            if (album == null) {
                throw new ResourceNotFoundException(Album.class, id);
            }

            final AlbumSummary summary = summaries(List.of(album)).getFirst();

            return new AlbumDetail(album.id(), album.title(), album.kind().nameEn(), album.kind().nameSv(), summary.spex(), summary.year(),
                    album.dateFrom(), album.dateTo(), album.place(), album.description(), summary.itemCount(), summary.url());
        });
    }

    @McpTool(name = "list_spex",
            description = "The spex with public albums in Spexgram, newest first, with how many public albums each has. A revival names the original it revives (originalId). Use a spex's id or title with list_albums and search_albums.",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public McpToolSupport.ListResult<Spex> listSpex() {
        return support.call(() -> new McpToolSupport.ListResult<>(albums.spexWithAlbums(McpToolSupport.VIEWER).stream()
                .map(AlbumMcpTools::toMcp).toList()));
    }

    /**
     * A spex an album is about.
     *
     * @param originalId the spex a revival revives, else null
     * @param category   e.g. Chalmersspexet
     * @param albumCount how many public albums are about it, where listed
     */
    public record Spex(UUID id, @Nullable String title, @Nullable Integer year, boolean revival, @Nullable UUID originalId,
                       @Nullable String category, @Nullable Integer albumCount) {
    }

    /**
     * A public album in a list.
     *
     * @param year      its main spex's year, else its date's
     * @param itemCount how many public photos and videos it has
     * @param url       its page in Spexgram Web
     */
    public record AlbumSummary(UUID id, String title, List<Spex> spex, @Nullable Integer year, @Nullable String dateFrom,
                               @Nullable String dateTo, int itemCount, @Nullable String url) {
    }

    public record AlbumList(List<AlbumSummary> items, long totalCount, int page, int size) {
    }

    /**
     * A public album.
     *
     * @param kind   the kind of album in English, e.g. Show
     * @param kindSv the kind in Swedish
     */
    public record AlbumDetail(UUID id, String title, String kind, String kindSv, List<Spex> spex, @Nullable Integer year,
                              @Nullable String dateFrom, @Nullable String dateTo, @Nullable String place, @Nullable String description,
                              int itemCount, @Nullable String url) {
    }
}
