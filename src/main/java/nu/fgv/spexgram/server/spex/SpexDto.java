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

import java.util.UUID;

/**
 * A spex as Spexregister knows it.
 *
 * @param id           its id in Spexregister
 * @param title        its title, shared with its original and revivals
 * @param year         the year it was played
 * @param revival      whether it is a revival of an older spex
 * @param parentId     the original, for a revival
 * @param categoryId   its category, e.g. Chalmersspexet
 * @param categoryName the category's name
 * @author Anders Jacobsson
 * @since 1.0
 */
public record SpexDto(UUID id, @Nullable String title, @Nullable Integer year, boolean revival,
                      @Nullable UUID parentId, @Nullable UUID categoryId, @Nullable String categoryName) {
}
