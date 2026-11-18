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

import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.user.AppUser;
import nu.fgv.spexgram.server.user.AppUserRepository;
import nu.fgv.spexgram.server.user.CallerService;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.ViewerService;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Members' favourites (5.1 of the spec): every member marks the items they like, and sees them in a view of their
 * own. Favourites are personal and never change what others see; they go with the member's record.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class FavouriteService {

    private final FavouriteRepository repository;
    private final ItemRepository itemRepository;
    private final AppUserRepository userRepository;
    private final CallerService callerService;
    private final ViewerService viewerService;
    private final ItemViews views;
    private final Clock clock;

    public FavouriteService(final FavouriteRepository repository, final ItemRepository itemRepository,
                            final AppUserRepository userRepository, final CallerService callerService,
                            final ViewerService viewerService, final ItemViews views, final Clock clock) {
        this.repository = repository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.callerService = callerService;
        this.viewerService = viewerService;
        this.views = views;
        this.clock = clock;
    }

    /**
     * Items the member with this {@code AppUser} id has marked.
     */
    public static Specification<Item> markedBy(final long userId) {
        return (root, query, builder) -> {
            final Subquery<Long> marked = Objects.requireNonNull(query).subquery(Long.class);
            final Root<Favourite> favourite = marked.from(Favourite.class);

            marked.select(favourite.get("itemId")).where(builder.equal(favourite.get("userId"), userId));
            return root.get("id").in(marked);
        };
    }

    /**
     * The member's {@code AppUser} id, for their favourites.
     */
    long userId(final Viewer viewer) {
        final String subject = viewer.requireMember().subject();

        return userRepository.findBySubject(subject).map(AppUser::getId)
                .orElseGet(() -> callerService.record(viewer.requireMember()).getId());
    }

    /**
     * Marks the items the member may see as favourites, or unmarks them; the items, as the member sees them.
     */
    @Transactional
    public List<ItemDto> set(final List<UUID> ids, final boolean favourite) {
        final Viewer viewer = viewerService.current();
        final long userId = userId(viewer);

        if (ids.size() > ItemService.MAX_BULK) {
            throw new BadRequestException("error.item.tooMany", ItemService.MAX_BULK);
        }

        final List<Item> items = ids.isEmpty() ? List.of() : itemRepository.findByUuidIn(ids).stream()
                .filter(item -> ItemViews.canSee(item, viewer)).toList();
        final List<Long> itemIds = items.stream().map(Item::getId).toList();

        if (!itemIds.isEmpty()) {
            if (favourite) {
                final Set<Long> marked = repository.findItemIds(userId, itemIds);

                repository.saveAll(itemIds.stream().filter(id -> !marked.contains(id))
                        .map(id -> new Favourite(userId, id, clock.instant())).toList());
            } else {
                repository.deleteItems(userId, itemIds);
            }
        }

        final ItemViews.Context context = views.context(viewer);

        return items.stream().map(item -> views.toDto(item, context)).toList();
    }

    /**
     * Which of the items the viewer has marked; none for visitors.
     */
    @Transactional(readOnly = true)
    public Set<UUID> marked(final Collection<UUID> ids) {
        final Viewer viewer = viewerService.current();

        if (!viewer.isMember() || viewer.subject() == null || ids.isEmpty()) {
            return Set.of();
        }
        return userRepository.findBySubject(viewer.subject())
                .map(user -> repository.findItemUuids(user.getId(), ids))
                .orElse(Set.of());
    }
}
