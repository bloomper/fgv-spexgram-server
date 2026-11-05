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


package nu.fgv.spexgram.server.maintenance;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexgram.server.contribution.ContributionService;
import nu.fgv.spexgram.server.immich.ImmichUnavailableException;
import nu.fgv.spexgram.server.item.ItemProcessing;
import nu.fgv.spexgram.server.report.ReportService;
import nu.fgv.spexgram.server.spex.SpexService;
import nu.fgv.spexgram.server.upload.UploadService;
import nu.fgv.spexgram.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Runs the maintenance jobs on their schedules, one run of each at a time, and remembers how the latest run of each
 * went, whether the schedule or ADMIN started it. The runs are kept in memory until the application restarts.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class MaintenanceJobs {

    private final Map<MaintenanceJob, Runnable> work;
    private final Set<MaintenanceJob> running = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<MaintenanceJob, Run> lastRuns = new ConcurrentHashMap<>();

    @Autowired
    @SuppressWarnings("java:S107")
    public MaintenanceJobs(final SpexService spexService, final ItemProcessing itemProcessing, final UploadService uploadService,
                           final CallerService callerService, final ReportService reportService,
                           final ContributionService contributionService) {
        this(Map.of(
                MaintenanceJob.SPEX_REFRESH, spexService::refresh,
                MaintenanceJob.UNSORTED_SCAN, itemProcessing::scanUnsorted,
                MaintenanceJob.PROCESSING_POLL, itemProcessing::poll,
                MaintenanceJob.ITEM_CHECK, itemProcessing::checkExistence,
                MaintenanceJob.UPLOAD_CLEANUP, uploadService::cleanUp,
                MaintenanceJob.RETENTION, () -> {
                    callerService.cleanUp();
                    reportService.cleanUp();
                    contributionService.cleanUp();
                },
                MaintenanceJob.CONTRIBUTION_MAIL, contributionService::sendReviewMails
        ));
    }

    MaintenanceJobs(final Map<MaintenanceJob, Runnable> work) {
        this.work = work;
    }

    @Scheduled(cron = "${spexgram.jobs.spex-refresh.cron-expression}", zone = "${spexgram.time-zone}")
    void spexRefresh() {
        run(MaintenanceJob.SPEX_REFRESH);
    }

    @Scheduled(cron = "${spexgram.jobs.unsorted-scan.cron-expression}", zone = "${spexgram.time-zone}")
    void unsortedScan() {
        run(MaintenanceJob.UNSORTED_SCAN);
    }

    @Scheduled(cron = "${spexgram.jobs.processing-poll.cron-expression}", zone = "${spexgram.time-zone}")
    void processingPoll() {
        run(MaintenanceJob.PROCESSING_POLL);
    }

    @Scheduled(cron = "${spexgram.jobs.item-check.cron-expression}", zone = "${spexgram.time-zone}")
    void itemCheck() {
        run(MaintenanceJob.ITEM_CHECK);
    }

    @Scheduled(cron = "${spexgram.jobs.upload-cleanup.cron-expression}", zone = "${spexgram.time-zone}")
    void uploadCleanup() {
        run(MaintenanceJob.UPLOAD_CLEANUP);
    }

    @Scheduled(cron = "${spexgram.jobs.retention.cron-expression}", zone = "${spexgram.time-zone}")
    void retention() {
        run(MaintenanceJob.RETENTION);
    }

    @Scheduled(cron = "${spexgram.jobs.contribution-mail.cron-expression}", zone = "${spexgram.time-zone}")
    void contributionMail() {
        run(MaintenanceJob.CONTRIBUTION_MAIL);
    }

    /**
     * Runs the job in the calling thread unless it is already running.
     *
     * @param job the job
     * @return whether it ran
     */
    public boolean run(final MaintenanceJob job) {
        if (!running.add(job)) {
            log.info("Not starting {}, it is already running", job);
            return false;
        }

        final Instant startedAt = Instant.now();
        boolean succeeded = false;

        lastRuns.put(job, new Run(startedAt, null, null));
        try {
            work.get(job).run();
            succeeded = true;
        } catch (final ImmichUnavailableException e) {
            log.warn("{} failed: Immich is unavailable", job);
        } catch (final RuntimeException e) {
            log.error("{} failed", job, e);
        } finally {
            lastRuns.put(job, new Run(startedAt, Instant.now(), succeeded));
            running.remove(job);
        }
        return true;
    }

    /**
     * @param job the job
     * @return whether it is running
     */
    public boolean isRunning(final MaintenanceJob job) {
        return running.contains(job);
    }

    /**
     * @param job the job
     * @return its latest run since the application started
     */
    public Optional<Run> lastRun(final MaintenanceJob job) {
        return Optional.ofNullable(lastRuns.get(job));
    }

    /**
     * A run of a job.
     *
     * @param startedAt  when it started
     * @param finishedAt when it finished, or null while it runs
     * @param succeeded  whether it finished without an error, or null while it runs
     */
    public record Run(Instant startedAt, @Nullable Instant finishedAt, @Nullable Boolean succeeded) {
    }
}
