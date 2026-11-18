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

package nu.fgv.spexgram.server.spex;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * A spex as Spexipedia knows it.
 *
 * @param id           its id in Spexipedia
 * @param title        its title, shared with its original and revivals
 * @param year         the year it was played
 * @param revival      whether it is a revival of an older spex
 * @param parentId     the original, for a revival
 * @param categoryId   its category, e.g. Chalmersspexet
 * @param categoryName the category's name
 * @param retired      whether Spexipedia no longer offers it for new use
 * @param posterUrl    its poster's thumbnail (a revival without one has its original's), public
 * @param premiere     its first night, from its page; null when the page has none or the viewer may not see it
 * @author Anders Jacobsson
 * @since 1.0
 */
public record SpexDto(UUID id, @Nullable String title, @Nullable Integer year, boolean revival,
                      @Nullable UUID parentId, @Nullable UUID categoryId, @Nullable String categoryName,
                      boolean retired, @Nullable String posterUrl, @Nullable LocalDate premiere) {

    public SpexDto(final UUID id, final @Nullable String title, final @Nullable Integer year, final boolean revival,
                   final @Nullable UUID parentId, final @Nullable UUID categoryId, final @Nullable String categoryName) {
        this(id, title, year, revival, parentId, categoryId, categoryName, false, null, null);
    }

    /**
     * Spexipedia's day (yyyy-mm-dd) as a date; null when it isn't one.
     */
    public static @Nullable LocalDate day(final @Nullable String day) {
        try {
            return day == null ? null : LocalDate.parse(day);
        } catch (final DateTimeParseException _) {
            return null;
        }
    }

    /**
     * Spexipedia's year (yyyy) as a number; null when it isn't one.
     */
    public static @Nullable Integer year(final @Nullable String year) {
        try {
            return year == null ? null : Integer.valueOf(year);
        } catch (final NumberFormatException _) {
            return null;
        }
    }
}
