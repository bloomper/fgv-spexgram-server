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

import nu.fgv.spexgram.server.album.AlbumDtos.AlbumFilter;
import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.item.ItemDtos.MapItem;
import nu.fgv.spexgram.server.search.SearchService.SearchResult;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Locale;

/**
 * GraphQL for search and the map. A thin adapter: {@link SearchService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class SearchGraphqlApi {

    private final SearchService service;

    public SearchGraphqlApi(final SearchService service) {
        this.service = service;
    }

    @QueryMapping
    public SearchResult search(@Argument final String q, @Argument final @Nullable Integer limit) {
        return service.search(q, limit == null ? 50 : limit);
    }

    @QueryMapping
    public List<ItemDto> smartSearch(@Argument final String q, @Argument final @Nullable AlbumFilter filter,
                                     @Argument final @Nullable Integer limit, final Locale locale) {
        return service.smartSearch(q, filter, limit == null ? 50 : limit, locale);
    }

    @QueryMapping
    public List<MapItem> mapItems(@Argument final @Nullable AlbumFilter filter) {
        return service.mapItems(filter);
    }
}
