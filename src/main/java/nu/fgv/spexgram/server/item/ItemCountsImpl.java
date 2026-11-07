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

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import nu.fgv.spexgram.server.album.Album;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts and covers with a {@code GROUP BY}, so that a page of albums costs a fixed number of queries for their item
 * counts and covers rather than some per album.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ItemCountsImpl implements ItemCounts {

    private final EntityManager entityManager;

    ItemCountsImpl(final EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Map<Long, Long> countByAlbum(final Specification<Item> specification, final Collection<Album> albums) {
        if (albums.isEmpty()) {
            return Map.of();
        }

        final CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        final CriteriaQuery<Tuple> query = builder.createTupleQuery();
        final Root<Item> root = query.from(Item.class);
        final Predicate matches = specification.toPredicate(root, query, builder);
        final Path<Long> album = root.get("album").get("id");
        final Predicate inAlbums = album.in(albums.stream().map(Album::getId).toList());

        query.select(builder.tuple(album, builder.count(root)))
                .where(matches == null ? inAlbums : builder.and(matches, inAlbums))
                .groupBy(album);

        final Map<Long, Long> counts = new HashMap<>();

        for (final Tuple row : entityManager.createQuery(query).getResultList()) {
            counts.put(row.get(0, Long.class), row.get(1, Long.class));
        }
        return counts;
    }

    @Override
    public Map<Long, Long> firstByAlbum(final Specification<Item> specification, final Collection<Album> albums) {
        if (albums.isEmpty()) {
            return Map.of();
        }

        final List<Long> albumIds = albums.stream().map(Album::getId).toList();
        final CriteriaBuilder builder = entityManager.getCriteriaBuilder();

        // When each album's first item was taken; albums with only undated items are missing here
        final CriteriaQuery<Tuple> earliest = builder.createTupleQuery();
        final Root<Item> dated = earliest.from(Item.class);
        final Path<Long> datedAlbum = dated.get("album").get("id");
        final Path<LocalDateTime> takenAt = dated.get("takenAt");

        earliest.select(builder.tuple(datedAlbum, builder.least(takenAt)))
                .where(matching(specification, dated, earliest, builder, datedAlbum.in(albumIds)))
                .groupBy(datedAlbum);

        final Map<Long, LocalDateTime> firstTaken = new HashMap<>();

        for (final Tuple row : entityManager.createQuery(earliest).getResultList()) {
            final LocalDateTime at = row.get(1, LocalDateTime.class);

            if (at != null) {
                firstTaken.put(row.get(0, Long.class), at);
            }
        }

        // The lowest id among the items taken then, or among the undated items of albums with no dated one
        final CriteriaQuery<Tuple> first = builder.createTupleQuery();
        final Root<Item> root = first.from(Item.class);
        final Path<Long> album = root.get("album").get("id");
        final List<Predicate> candidates = new ArrayList<>();

        firstTaken.forEach((albumId, at) -> candidates.add(builder.and(builder.equal(album, albumId), builder.equal(root.get("takenAt"), at))));

        final List<Long> undated = albumIds.stream().filter(id -> !firstTaken.containsKey(id)).toList();

        if (!undated.isEmpty()) {
            candidates.add(builder.and(album.in(undated), builder.isNull(root.get("takenAt"))));
        }
        first.select(builder.tuple(album, builder.min(root.<Long>get("id"))))
                .where(matching(specification, root, first, builder, builder.or(candidates.toArray(Predicate[]::new))))
                .groupBy(album);

        final Map<Long, Long> ids = new HashMap<>();

        for (final Tuple row : entityManager.createQuery(first).getResultList()) {
            ids.put(row.get(0, Long.class), row.get(1, Long.class));
        }
        return ids;
    }

    private static Predicate matching(final Specification<Item> specification, final Root<Item> root, final CriteriaQuery<?> query,
                                      final CriteriaBuilder builder, final Predicate restriction) {
        final Predicate matches = specification.toPredicate(root, query, builder);

        return matches == null ? restriction : builder.and(matches, restriction);
    }
}
