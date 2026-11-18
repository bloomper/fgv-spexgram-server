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

import nu.fgv.spexgram.server.album.Album;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

import java.time.YearMonth;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Counting items per album or month in one query, for {@link ItemRepository}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface ItemCounts {

    /**
     * How many items of each album match the specification, by the album's id; albums without any are left out.
     */
    Map<Long, Long> countByAlbum(Specification<Item> specification, Collection<Album> albums);

    /**
     * The id of each album's first item that matches the specification, by when it was taken (items without a date
     * last, then by id), by the album's id; albums without any are left out. Two queries for any number of albums.
     */
    Map<Long, Long> firstByAlbum(Specification<Item> specification, Collection<Album> albums);

    /**
     * How many items match the specification in each month they were taken, newest month first; items without a
     * date are counted under null, last.
     */
    List<MonthCount> countByMonth(Specification<Item> specification);

    /**
     * @param month the month, or null for items without a date
     */
    record MonthCount(@Nullable YearMonth month, long count) {
    }
}
