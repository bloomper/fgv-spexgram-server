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

import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.item.ItemDtos.ItemPage;
import nu.fgv.spexgram.server.item.ItemDtos.TimelineMonth;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GraphQL for the timeline and favourites. A thin adapter: the services decide what the viewer may see and do.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class TimelineGraphqlApi {

    private final TimelineService timelineService;
    private final FavouriteService favouriteService;

    public TimelineGraphqlApi(final TimelineService timelineService, final FavouriteService favouriteService) {
        this.timelineService = timelineService;
        this.favouriteService = favouriteService;
    }

    @QueryMapping
    public List<TimelineMonth> timeline(@Argument final @Nullable Boolean favourites) {
        return timelineService.months(Boolean.TRUE.equals(favourites));
    }

    @QueryMapping
    public ItemPage timelineItems(@Argument final @Nullable String month, @Argument final @Nullable Boolean favourites,
                                  @Argument final @Nullable Integer page, @Argument final @Nullable Integer size) {
        return timelineService.items(month, Boolean.TRUE.equals(favourites), page == null ? 0 : page, size == null ? 200 : size);
    }

    @MutationMapping
    public List<ItemDto> favouritesSet(@Argument final List<UUID> itemIds, @Argument final boolean favourite) {
        return favouriteService.set(itemIds, favourite);
    }

    /**
     * Whether the viewer marked each item, in one query for a page of items.
     */
    @BatchMapping(typeName = "Item", field = "favourite")
    public Map<ItemDto, Boolean> favourite(final List<ItemDto> items) {
        final Set<UUID> marked = favouriteService.marked(items.stream().map(ItemDto::id).distinct().toList());
        final Map<ItemDto, Boolean> result = new LinkedHashMap<>();

        items.forEach(item -> result.put(item, marked.contains(item.id())));
        return result;
    }
}
