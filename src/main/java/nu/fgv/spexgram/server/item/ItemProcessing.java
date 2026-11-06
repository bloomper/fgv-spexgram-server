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

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import nu.fgv.spexgram.server.audit.AuditService;
import nu.fgv.spexgram.server.immich.ImmichClient;
import nu.fgv.spexgram.server.immich.ImmichUnavailableException;
import nu.fgv.spexgram.server.settings.SettingsService;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Follows what Immich does with the files (section 8 of the spec): items are {@code PROCESSING} until Immich has made
 * their thumbnails and, for videos, transcoded them when they need it; then their size, date taken and position are
 * copied. Videos longer than the setting allows are rejected and their files deleted. Also finds assets Immich has that
 * Spexgram does not know, e.g. from a bulk import, and lists them as unsorted (5.6), and notices items whose file is
 * gone from Immich.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class ItemProcessing {

    static final Duration GIVE_UP = Duration.ofHours(6);
    /**
     * How long an asset Immich just got is left alone by the scans: its upload may not have made its item yet.
     */
    static final Duration SETTLE = Duration.ofMinutes(10);

    private final ItemRepository repository;
    private final ImmichClient immichClient;
    private final SettingsService settingsService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ItemProcessing(final ItemRepository repository, final ImmichClient immichClient,
                          final SettingsService settingsService, final AuditService auditService,
                          final TransactionTemplate transactionTemplate, final Clock clock) {
        this.repository = repository;
        this.immichClient = immichClient;
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * Copies what Immich knows of an asset onto its item; the date taken only while the item is processing, since
     * editors may change it afterwards.
     */
    static void copy(final ImmichClient.Asset asset, final Item item) {
        item.setType(asset.video() ? ItemType.VIDEO : ItemType.PHOTO);
        item.setWidth(asset.width());
        item.setHeight(asset.height());
        item.setDurationMs(asset.durationMs());
        if (asset.fileSize() != null) {
            item.setFileSize(asset.fileSize());
        }
        if (item.getStatus() == ItemStatus.PROCESSING) {
            item.setTakenAt(asset.takenAt());
            item.setLatitude(asset.latitude());
            item.setLongitude(asset.longitude());
        }
    }

    /**
     * Checks every item Immich is processing.
     */
    @SchedulerLock(name = "processing-poll", lockAtMostFor = "PT10M")
    public void poll() {
        final List<Item> processing = repository.findByStatus(ItemStatus.PROCESSING);

        if (processing.isEmpty()) {
            return;
        }

        for (final Item item : processing) {
            refreshOne(item.getId());
        }
    }

    /**
     * Checks one item now, e.g. right after its upload.
     */
    public void refresh(final Long itemId) {
        try {
            refreshOne(itemId);
        } catch (final ImmichUnavailableException e) {
            log.debug("Could not check item {} in Immich", itemId, e);
        }
    }

    private void refreshOne(final Long itemId) {
        final Item snapshot = repository.findById(itemId).filter(item -> item.getStatus() == ItemStatus.PROCESSING).orElse(null);

        if (snapshot == null || snapshot.getImmichAssetId() == null) {
            return;
        }

        final UUID assetId = snapshot.getImmichAssetId();
        final Optional<ImmichClient.Asset> asset = immichClient.asset(assetId);
        // A video is ready only once transcoded: Immich transcodes every one, and the browser version carries none of
        // the original's metadata (location, creation time), which viewers who may not download originals must not get
        final boolean transcoded = asset.filter(found -> found.video() && found.hasThumbnail())
                .map(_ -> immichClient.isEncoded(assetId))
                .orElse(false);
        final boolean ready = asset.filter(ImmichClient.Asset::hasThumbnail)
                .map(found -> !found.video() || transcoded)
                .orElse(false);
        final long maxVideoMs = Duration.ofMinutes(settingsService.current().getMaxVideoMinutes()).toMillis();

        try {
            transactionTemplate.executeWithoutResult(_ -> {
                final Item item = repository.findById(itemId).orElse(null);

                if (item == null || item.getStatus() != ItemStatus.PROCESSING || !assetId.equals(item.getImmichAssetId())) {
                    return;
                }
                if (asset.isEmpty()) {
                    fail(item, ItemFailure.MISSING);
                    return;
                }
                copy(asset.get(), item);
                item.setTranscoded(transcoded);
                if (asset.get().video() && item.getDurationMs() != null && item.getDurationMs() > maxVideoMs) {
                    immichClient.delete(List.of(assetId));
                    item.setImmichAssetId(null);
                    fail(item, ItemFailure.TOO_LONG);
                } else if (ready) {
                    item.setStatus(ItemStatus.READY);
                    item.setProcessingSince(null);
                } else if (item.getProcessingSince() != null && item.getProcessingSince().plus(GIVE_UP).isBefore(clock.instant())) {
                    fail(item, ItemFailure.NOT_PROCESSED);
                }
            });
        } catch (final OptimisticLockingFailureException e) {
            // An editor changed the item meanwhile; the next poll looks again
            log.debug("Item {} changed while it was checked", itemId);
        }
    }

    /**
     * Whether the item has not changed since the listing of Immich's assets started, so its asset should be in it.
     */
    private static boolean changedBefore(final Item item, final Instant started) {
        final Instant changed = item.getLastModifiedAt() != null ? item.getLastModifiedAt() : item.getCreatedAt();

        return changed != null && changed.isBefore(started);
    }

    private void fail(final Item item, final ItemFailure failure) {
        item.setStatus(ItemStatus.FAILED);
        item.setFailure(failure);
        item.setProcessingSince(null);
        log.info("Item {} failed: {}", item.getUuid(), failure);
    }

    /**
     * Lists every asset in Immich that no item refers to as an unsorted item, e.g. after a bulk import with the command
     * line tool.
     */
    @SchedulerLock(name = "unsorted-scan", lockAtMostFor = "PT30M")
    public void scanUnsorted() {
        final Set<UUID> known = new HashSet<>(repository.findAllAssetIds());
        final AtomicInteger found = new AtomicInteger();
        final Instant settled = clock.instant().minus(SETTLE);

        immichClient.forEachAsset(asset -> {
            if (known.contains(asset.id()) || asset.createdAt() == null || asset.createdAt().isAfter(settled)) {
                return;
            }
            transactionTemplate.executeWithoutResult(_ -> {
                final Item item = new Item();

                item.setImmichAssetId(asset.id());
                item.setOriginalFileName(asset.originalFileName());
                item.setVisibility(Visibility.UNPUBLISHED);
                item.setStatus(ItemStatus.PROCESSING);
                item.setProcessingSince(clock.instant());
                copy(asset, item);
                // A video waits for its browser version, which the processing poll follows
                if (asset.hasThumbnail() && !asset.video()) {
                    item.setStatus(ItemStatus.READY);
                    item.setProcessingSince(null);
                }
                repository.save(item);
            });
            found.incrementAndGet();
        });
        if (found.get() > 0) {
            auditService.record("CREATE", "ITEM", null, "Hittade %d osorterade bilder och filmer i Immich".formatted(found.get()));
            log.info("Found {} unsorted items in Immich", found.get());
        }
    }

    /**
     * Marks items whose file Immich no longer has as failed.
     */
    @SchedulerLock(name = "item-check", lockAtMostFor = "PT30M")
    public void checkExistence() {
        final Set<UUID> inImmich = new HashSet<>();
        final Instant started = clock.instant();

        immichClient.forEachAsset(asset -> inImmich.add(asset.id()));
        transactionTemplate.executeWithoutResult(_ -> {
            int missing = 0;

            for (final Item item : repository.findAll()) {
                if (item.getImmichAssetId() != null && !inImmich.contains(item.getImmichAssetId()) && item.getFailure() != ItemFailure.MISSING
                        && changedBefore(item, started)) {
                    fail(item, ItemFailure.MISSING);
                    missing++;
                }
            }
            if (missing > 0) {
                log.warn("{} items' files are missing in Immich", missing);
            }
        });
    }
}
