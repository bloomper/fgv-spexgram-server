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

import nu.fgv.spexgram.server.item.ItemDtos.ItemPage;
import nu.fgv.spexgram.server.item.ItemDtos.TimelineMonth;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.ViewerService;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The timeline (5.1 of the spec): every item the viewer may see, across albums, newest first, as a photo library
 * shows it. The months come first, with how many items each has, so that the web can lay out and scrub through
 * thousands of items before it loads them; then each month's items, a page at a time. The favourites view is the same
 * timeline narrowed to the member's favourites.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class TimelineService {

    static final int MAX_PAGE = 500;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("takenAt"), Sort.Order.desc("id"));

    private final ItemRepository repository;
    private final ViewerService viewerService;
    private final FavouriteService favouriteService;
    private final ItemViews views;

    public TimelineService(final ItemRepository repository, final ViewerService viewerService,
                           final FavouriteService favouriteService, final ItemViews views) {
        this.repository = repository;
        this.viewerService = viewerService;
        this.favouriteService = favouriteService;
        this.views = views;
    }

    /**
     * The month, yyyy-mm, or null for items without a date.
     */
    static @Nullable YearMonth month(final @Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return YearMonth.parse(value.strip());
        } catch (final DateTimeParseException e) {
            throw new BadRequestException("error.timeline.month");
        }
    }

    static Specification<Item> takenIn(final @Nullable YearMonth month) {
        if (month == null) {
            return (root, query, builder) -> builder.isNull(root.get("takenAt"));
        }

        final LocalDateTime from = month.atDay(1).atStartOfDay();
        final LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();

        return (root, query, builder) -> builder.and(builder.greaterThanOrEqualTo(root.get("takenAt"), from),
                builder.lessThan(root.get("takenAt"), to));
    }

    private Specification<Item> visible(final Viewer viewer, final boolean favourites) {
        final Specification<Item> visible = ItemSpecifications.visibleTo(viewer);

        return favourites ? visible.and(FavouriteService.markedBy(favouriteService.userId(viewer))) : visible;
    }

    /**
     * The months of the viewer's timeline, newest first, with how many items each has; items without a date last.
     * With favourites, only the member's favourites.
     */
    @Transactional(readOnly = true)
    public List<TimelineMonth> months(final boolean favourites) {
        final Viewer viewer = viewerService.current();

        return repository.countByMonth(visible(viewer, favourites)).stream()
                .map(count -> new TimelineMonth(count.month() == null ? null : count.month().toString(), (int) count.count()))
                .toList();
    }

    /**
     * A page of one month's items, newest first.
     *
     * @param month yyyy-mm, or null for the items without a date
     */
    @Transactional(readOnly = true)
    public ItemPage items(final @Nullable String month, final boolean favourites, final int page, final int size) {
        final Viewer viewer = viewerService.current();
        final int p = Math.max(page, 0);
        final int s = Math.clamp(size, 1, MAX_PAGE);
        final Page<Item> items = repository.findAll(visible(viewer, favourites).and(takenIn(month(month))), PageRequest.of(p, s, NEWEST_FIRST));
        final ItemViews.Context context = views.context(viewer);

        return new ItemPage(items.getContent().stream().map(item -> views.toDto(item, context)).toList(), items.getTotalElements(), p, s);
    }
}
