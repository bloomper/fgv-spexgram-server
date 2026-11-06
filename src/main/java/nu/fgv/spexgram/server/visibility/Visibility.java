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


package nu.fgv.spexgram.server.visibility;

/**
 * Who sees an album or an item (section 4.1 of the spec), from the narrowest to the widest.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum Visibility {

    /**
     * Editors and admins.
     */
    UNPUBLISHED,
    /**
     * Everyone with {@code USER}.
     */
    MEMBERS,
    /**
     * Everyone, also visitors who are not logged in.
     */
    PUBLIC;

    /**
     * The visibility an item is seen with: the narrower of its own and its album's, except that an item marked public
     * is public on its own in an album for members. Nothing in an unpublished or trashed album is shown outside the
     * editors, and neither is a trashed or unsorted item.
     *
     * @param album         the album's visibility, or null for an unsorted item
     * @param albumTrashed  whether the album is in the trash
     * @param item          the item's own visibility
     * @param itemTrashed   whether the item is in the trash
     */
    public static Visibility effective(final @org.jspecify.annotations.Nullable Visibility album, final boolean albumTrashed,
                                       final Visibility item, final boolean itemTrashed) {
        if (album == null || albumTrashed || itemTrashed || album == UNPUBLISHED) {
            return UNPUBLISHED;
        }
        if (item == PUBLIC) {
            return PUBLIC;
        }
        return item.compareTo(album) <= 0 ? item : album;
    }
}
