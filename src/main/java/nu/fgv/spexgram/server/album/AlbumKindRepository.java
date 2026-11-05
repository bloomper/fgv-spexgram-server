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


package nu.fgv.spexgram.server.album;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link AlbumKind}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface AlbumKindRepository extends JpaRepository<AlbumKind, Long> {

    Optional<AlbumKind> findByUuid(UUID uuid);

    List<AlbumKind> findAllByOrderByPositionAscIdAsc();

    Optional<AlbumKind> findFirstByOrderByPositionAscIdAsc();

    /**
     * How many albums, trashed ones included, each kind has, as pairs of kind id and count.
     */
    @Query("SELECT a.kind.id, COUNT(a) FROM Album a GROUP BY a.kind.id")
    List<Object[]> countAlbumsByKind();

    @Query("SELECT COUNT(a) FROM Album a WHERE a.kind = :kind")
    long countAlbums(@Param("kind") AlbumKind kind);
}
