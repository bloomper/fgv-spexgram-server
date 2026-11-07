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
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Repository for {@link Item}. Queries for viewers go through {@link ItemSpecifications}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface ItemRepository extends JpaRepository<Item, Long>, JpaSpecificationExecutor<Item>, ItemCounts {

    Optional<Item> findByUuid(UUID uuid);

    List<Item> findByUuidIn(Collection<UUID> uuids);

    Optional<Item> findByImmichAssetId(UUID assetId);

    List<Item> findByImmichAssetIdIn(Collection<UUID> assetIds);

    List<Item> findByStatus(ItemStatus status);

    List<Item> findByAlbumAndTrashedAtIsNull(Album album);

    List<Item> findByAlbum(Album album);

    /**
     * Unsorted items, outside the trash.
     */
    List<Item> findByAlbumIsNullAndTrashedAtIsNullOrderByTakenAtAscIdAsc();

    List<Item> findByTrashedAtIsNotNullOrderByTrashedAtDesc();

    List<Item> findByContributionStatusOrderByCreatedAtAsc(ContributionStatus status);

    List<Item> findByUploadedBySubjectAndContributionStatusIsNotNullOrderByCreatedAtDesc(String subject);

    /**
     * The items among these that an open report hides, which keep their visibility until it is handled.
     */
    @Query("SELECT DISTINCT r.item.id FROM Report r WHERE r.status = nu.fgv.spexgram.server.report.Report.Status.OPEN AND r.hiddenFrom IS NOT NULL AND r.item IN :items")
    Set<Long> findHiddenByOpenReports(@Param("items") Collection<Item> items);

    @Query("SELECT i.immichAssetId FROM Item i WHERE i.immichAssetId IS NOT NULL")
    Set<UUID> findAllAssetIds();

    @Query("SELECT COUNT(i) FROM Item i WHERE i.uploadedBySubject = :subject AND i.contributionStatus IS NOT NULL AND i.createdAt >= :since")
    long countContributionsSince(@Param("subject") String subject, @Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(i.fileSize), 0) FROM Item i WHERE i.uploadedBySubject = :subject AND i.contributionStatus IS NOT NULL AND i.createdAt >= :since")
    long sumContributionBytesSince(@Param("subject") String subject, @Param("since") Instant since);
}
