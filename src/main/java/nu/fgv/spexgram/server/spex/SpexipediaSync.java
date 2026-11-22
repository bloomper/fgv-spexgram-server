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

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import nu.fgv.spexgram.server.config.SpexgramProperties;
import nu.fgv.spexgram.server.util.StoredText;
import nu.fgv.spexgram.server.util.TextException;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Follows Spexipedia's change feed (Spexipedia's spec, 5.8): every five minutes and when Spexipedia says there is
 * something new ({@code POST /api/sync/notify}), reads pages until it has caught up and applies each page in one
 * transaction with the cursor. It updates the spex copied onto albums and category names; a merge moves those
 * references to the record merged into.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class SpexipediaSync {

    private static final int MAX_ERROR = 1000;
    private static final int MAX_PAGES = 1000;
    static final int MAX_ATTEMPTS = 5;
    private static final int MAX_SKIPPED = 50;

    private final SpexipediaClient client;
    private final SpexgramProperties properties;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;
    private final CacheManager cacheManager;
    private final Clock clock;
    private final ReentrantLock running = new ReentrantLock();

    public SpexipediaSync(final SpexipediaClient client, final SpexgramProperties properties, final JdbcClient jdbc,
                          final PlatformTransactionManager transactionManager, final CacheManager cacheManager,
                          final Clock clock) {
        this.client = client;
        this.properties = properties;
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.cacheManager = cacheManager;
        this.clock = clock;
    }

    @Scheduled(cron = "${spexgram.jobs.spexipedia-sync.cron-expression}")
    @SchedulerLock(name = "spexipedia-sync", lockAtMostFor = "PT10M")
    void scheduled() {
        if (properties.spexipedia().syncEnabled()) {
            pull();
        }
    }

    /**
     * Pulls in the background, when Spexipedia says there is something new.
     */
    public void pullSoon() {
        if (properties.spexipedia().syncEnabled()) {
            Executors.newVirtualThreadPerTaskExecutor().execute(this::pull);
        }
    }

    /**
     * Pulls until caught up; a pull that finds another running returns at once.
     *
     * @return how many changes were applied, or empty when another pull was running or this one failed
     */
    public Optional<Integer> pull() {
        if (!running.tryLock()) {
            return Optional.empty();
        }
        try {
            return pullLocked();
        } finally {
            running.unlock();
        }
    }

    private Optional<Integer> pullLocked() {
        String cursor = jdbc.sql("SELECT cursor_value FROM spexipedia_sync WHERE id = 1").query(String.class).optional().orElse(null);
        int applied = 0;

        jdbc.sql("UPDATE spexipedia_sync SET last_pulled_at = :at WHERE id = 1").param("at", now()).update();
        try {
            for (int pages = 0; pages < MAX_PAGES; pages++) {
                final FeedChange.Page page = client.changes(cursor);
                final String next = page.cursor() == null ? cursor : page.cursor();

                applyPage(page, next);
                applied += page.changes().size();
                cursor = next;
                if (!page.hasMore()) {
                    break;
                }
            }
        } catch (final RuntimeException e) {
            final StoredText error = TextException.text(e);

            log.warn("Sync from Spexipedia failed after {} changes: {}", applied, e.toString());
            jdbc.sql("UPDATE spexipedia_sync SET last_error = :error WHERE id = 1")
                    .param("error", error.encode(MAX_ERROR)).update();
            return Optional.empty();
        }
        jdbc.sql("UPDATE spexipedia_sync SET last_success = :at, last_error = NULL WHERE id = 1").param("at", now()).update();
        if (applied > 0) {
            log.info("Applied {} changes from Spexipedia", applied);
            cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).clear());
        }
        return Optional.of(applied);
    }

    /**
     * Applies a page in one transaction with the cursor. When that fails its changes are applied one at a time, so that
     * the ones before the failing one are kept. A change that keeps failing is tried {@value #MAX_ATTEMPTS} times, at
     * as many pulls; then it is recorded as skipped, for admins to see, and the feed goes on without it.
     */
    void applyPage(final FeedChange.Page page, final @Nullable String next) {
        try {
            transactionTemplate.executeWithoutResult(_ -> {
                page.changes().forEach(this::apply);
                moveCursor(next, page.changes().size());
            });
            return;
        } catch (final RuntimeException e) {
            log.warn("A page of {} changes from Spexipedia could not be applied, applying them one at a time: {}",
                    page.changes().size(), message(e));
        }
        for (final FeedChange change : page.changes()) {
            try {
                transactionTemplate.executeWithoutResult(_ -> {
                    apply(change);
                    moveCursor(change.sequence(), 1);
                });
            } catch (final RuntimeException e) {
                if (attempt(change) < MAX_ATTEMPTS) {
                    throw e;
                }
                skip(change, message(e));
            }
        }
        transactionTemplate.executeWithoutResult(_ -> moveCursor(next, 0));
    }

    /**
     * Moves the cursor; whatever was failing before it no longer is.
     */
    private void moveCursor(final @Nullable String cursor, final int applied) {
        jdbc.sql("""
                        UPDATE spexipedia_sync SET cursor_value = :cursor, applied = applied + :n, failing_sequence = NULL, failing_attempts = 0
                        WHERE id = 1""")
                .param("cursor", cursor).param("n", applied).update();
    }

    /**
     * Counts one more failed attempt at the change and answers how many there have been.
     */
    private int attempt(final FeedChange change) {
        jdbc.sql("""
                        UPDATE spexipedia_sync
                        SET failing_attempts = CASE WHEN failing_sequence = :sequence THEN failing_attempts + 1 ELSE 1 END,
                            failing_sequence = :sequence
                        WHERE id = 1""")
                .param("sequence", change.sequence()).update();
        return jdbc.sql("SELECT failing_attempts FROM spexipedia_sync WHERE id = 1").query(Integer.class).single();
    }

    private void skip(final FeedChange change, final StoredText error) {
        log.error("Skipped change {} from Spexipedia ({} {} {}) after {} attempts: {}", change.sequence(), change.change(),
                change.kind(), change.id(), MAX_ATTEMPTS, error);
        transactionTemplate.executeWithoutResult(_ -> {
            jdbc.sql("""
                            INSERT INTO spexipedia_sync_skipped (sequence, kind, record_id, change_type, error, skipped_at)
                            VALUES (:sequence, :kind, :id, :change, :error, :at)""")
                    .param("sequence", change.sequence()).param("kind", change.kind()).param("id", change.id().toString())
                    .param("change", change.change()).param("error", error.encode(MAX_ERROR))
                    .param("at", now()).update();
            moveCursor(change.sequence(), 0);
        });
    }

    private static StoredText message(final RuntimeException e) {
        return TextException.text(e);
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * Applies changes in one transaction without moving the cursor, for tests.
     */
    public void applyAll(final List<FeedChange> changes) {
        transactionTemplate.executeWithoutResult(_ -> changes.forEach(this::apply));
    }

    void apply(final FeedChange change) {
        final String id = change.id().toString();

        if ("MERGE".equals(change.change())) {
            final String into = Objects.requireNonNull(change.mergedIntoId()).toString();

            switch (change.kind()) {
                case "SPEX" -> {
                    jdbc.sql("UPDATE album_spex SET spex_id = :into WHERE spex_id = :from").param("into", into).param("from", id).update();
                    jdbc.sql("UPDATE album_spex SET parent_id = :into WHERE parent_id = :from").param("into", into).param("from", id).update();
                    dropRepeated(into);
                }
                case "SPEX_CATEGORY" -> jdbc.sql("""
                                UPDATE album_spex SET category_id = :into,
                                    category_name = COALESCE((SELECT name FROM spexipedia_category WHERE id = :into), category_name)
                                WHERE category_id = :from""")
                        .param("into", into).param("from", id).update();
                default -> {
                    // Kinds Spexgram does not keep
                }
            }
            return;
        }
        switch (change.kind()) {
            case "SPEX_CATEGORY" -> {
                final FeedChange.SpexCategory category = Objects.requireNonNull(change.spexCategory());

                jdbc.sql("INSERT INTO spexipedia_category (id, name) VALUES (:id, :name) ON DUPLICATE KEY UPDATE name = :name")
                        .param("id", id).param("name", category.name()).update();
                jdbc.sql("UPDATE album_spex SET category_name = :name WHERE category_id = :id")
                        .param("name", category.name()).param("id", id).update();
            }
            case "SPEX" -> {
                final FeedChange.Spex spex = Objects.requireNonNull(change.spex());

                jdbc.sql("""
                                UPDATE album_spex SET title = :title, year = :year, revival = :revival, parent_id = :parent,
                                    category_id = :category,
                                    category_name = COALESCE((SELECT name FROM spexipedia_category WHERE id = :category), category_name),
                                    refreshed_at = :at
                                WHERE spex_id = :id""")
                        .param("title", spex.title()).param("year", SpexDto.year(spex.year()))
                        .param("revival", spex.parentId() != null)
                        .param("parent", spex.parentId() == null ? null : spex.parentId().toString())
                        .param("category", spex.categoryId() == null ? null : spex.categoryId().toString())
                        .param("at", now()).param("id", id).update();
            }
            default -> {
                // Kinds Spexgram does not keep
            }
        }
    }

    /**
     * After a merge an album about both spex would name the same one twice: the first stays and the positions close up.
     */
    private void dropRepeated(final String spexId) {
        final List<Long> owners = jdbc.sql("SELECT album_id FROM album_spex WHERE spex_id = :spex GROUP BY album_id HAVING COUNT(*) > 1")
                .param("spex", spexId).query(Long.class).list();

        for (final Long owner : owners) {
            final List<Map<String, Object>> rows = jdbc.sql("SELECT position, spex_id FROM album_spex WHERE album_id = :owner ORDER BY position")
                    .param("owner", owner).query().listOfRows();
            final Set<String> seen = new HashSet<>();
            int next = ((Number) rows.getFirst().get("position")).intValue();

            for (final Map<String, Object> row : rows) {
                final int position = ((Number) row.get("position")).intValue();

                if (!seen.add((String) row.get("spex_id"))) {
                    jdbc.sql("DELETE FROM album_spex WHERE album_id = :owner AND position = :position")
                            .param("owner", owner).param("position", position).update();
                    continue;
                }
                if (position != next) {
                    jdbc.sql("UPDATE album_spex SET position = :next WHERE album_id = :owner AND position = :position")
                            .param("next", next).param("owner", owner).param("position", position).update();
                }
                next++;
            }
        }
    }

    public Status status() {
        final List<Skipped> skipped = jdbc.sql("""
                        SELECT sequence, kind, record_id, change_type, error, skipped_at FROM spexipedia_sync_skipped
                        ORDER BY id DESC LIMIT :max""")
                .param("max", MAX_SKIPPED)
                .query((rs, _) -> new Skipped(rs.getString("sequence"), rs.getString("kind"), rs.getString("record_id"),
                        rs.getString("change_type"), rs.getString("error"), rs.getTimestamp("skipped_at").toInstant()))
                .list();

        return jdbc.sql("SELECT cursor_value, last_pulled_at, last_success, last_error, applied FROM spexipedia_sync WHERE id = 1")
                .query((rs, _) -> new Status(rs.getString("cursor_value"),
                        rs.getTimestamp("last_pulled_at") == null ? null : rs.getTimestamp("last_pulled_at").toInstant(),
                        rs.getTimestamp("last_success") == null ? null : rs.getTimestamp("last_success").toInstant(),
                        rs.getString("last_error"), rs.getLong("applied"), skipped))
                .single();
    }

    /**
     * Where the copy has got to in the feed, and the latest changes that were skipped.
     */
    public record Status(@Nullable String cursor, @Nullable Instant lastPulledAt, @Nullable Instant lastSuccess,
                         @Nullable String lastError, long applied, List<Skipped> skipped) {
    }

    /**
     * A change that could not be applied and was left out: the copy lacks it until it is made again in Spexipedia.
     */
    public record Skipped(String sequence, String kind, String id, String change, String error, Instant skippedAt) {
    }
}
