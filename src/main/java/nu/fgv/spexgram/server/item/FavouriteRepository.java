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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Repository for {@link Favourite}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface FavouriteRepository extends JpaRepository<Favourite, Favourite.Key> {

    /**
     * The items among these that the member has marked, by their ids.
     */
    @Query("SELECT f.itemId FROM Favourite f WHERE f.userId = :user AND f.itemId IN :items")
    Set<Long> findItemIds(@Param("user") Long userId, @Param("items") Collection<Long> itemIds);

    /**
     * The items among these that the member has marked, by their uuids.
     */
    @Query("SELECT i.uuid FROM Favourite f JOIN Item i ON i.id = f.itemId WHERE f.userId = :user AND i.uuid IN :items")
    Set<UUID> findItemUuids(@Param("user") Long userId, @Param("items") Collection<UUID> itemUuids);

    @Modifying
    @Query("DELETE FROM Favourite f WHERE f.userId = :user AND f.itemId IN :items")
    int deleteItems(@Param("user") Long userId, @Param("items") Collection<Long> itemIds);
}
