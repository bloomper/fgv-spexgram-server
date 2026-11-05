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

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Which albums and items a viewer sees (4.1 of the spec), as JPA predicates, so that every listing, count and search
 * applies the same rule as {@link Visibility#effective} and {@link ItemViews#canSee}:
 *
 * <ul>
 *     <li>Editors see every album and item outside the trash, unpublished, processing and failed ones included.</li>
 *     <li>Members see published albums and their members' and public items that are ready, the albums they suggested
 *     while they wait, and their own contributions waiting for review.</li>
 *     <li>Visitors see public albums, and public items in public albums and in albums for members.</li>
 * </ul>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class ItemSpecifications {

    private static final List<Visibility> MEMBERS_AND_PUBLIC = List.of(Visibility.MEMBERS, Visibility.PUBLIC);

    private ItemSpecifications() {
    }

    /**
     * Items in an album the viewer may see, outside the trash; not unsorted ones.
     */
    public static Specification<Item> visibleTo(final Viewer viewer) {
        return (root, query, builder) -> {
            final Join<Item, Album> album = root.join("album", JoinType.INNER);

            return builder.and(builder.isNull(root.get("trashedAt")), builder.isNull(album.get("trashedAt")),
                    itemRule(root, album, viewer, builder));
        };
    }

    private static Predicate itemRule(final Root<Item> root, final Join<Item, Album> album, final Viewer viewer,
                                      final CriteriaBuilder builder) {
        if (viewer.isEditor()) {
            return builder.conjunction();
        }

        final Predicate ready = builder.equal(root.get("status"), ItemStatus.READY);
        final Path<Visibility> itemVisibility = root.get("visibility");
        final Path<Visibility> albumVisibility = album.get("visibility");

        if (viewer.kind() == Viewer.Kind.VISITOR) {
            return builder.and(ready, builder.equal(itemVisibility, Visibility.PUBLIC), albumVisibility.in(MEMBERS_AND_PUBLIC));
        }

        final List<Predicate> any = new ArrayList<>();

        any.add(builder.and(ready, itemVisibility.in(MEMBERS_AND_PUBLIC), albumVisibility.in(MEMBERS_AND_PUBLIC)));
        if (viewer.subject() != null) {
            any.add(builder.and(builder.equal(root.get("uploadedBySubject"), viewer.subject()),
                    builder.equal(root.get("contributionStatus"), ContributionStatus.WAITING)));
        }
        return builder.or(any.toArray(Predicate[]::new));
    }

    /**
     * Albums the viewer may see, outside the trash.
     */
    public static Specification<Album> albumsVisibleTo(final Viewer viewer) {
        return (root, query, builder) -> builder.and(builder.isNull(root.get("trashedAt")), albumRule(root, viewer, builder));
    }

    static Predicate albumRule(final Path<Album> album, final Viewer viewer, final CriteriaBuilder builder) {
        if (viewer.isEditor()) {
            return builder.conjunction();
        }
        if (viewer.kind() == Viewer.Kind.VISITOR) {
            return builder.equal(album.get("visibility"), Visibility.PUBLIC);
        }

        final Predicate published = album.get("visibility").in(MEMBERS_AND_PUBLIC);

        return viewer.subject() == null ? published : builder.or(published, builder.equal(album.get("suggestedBy"), viewer.subject()));
    }
}
