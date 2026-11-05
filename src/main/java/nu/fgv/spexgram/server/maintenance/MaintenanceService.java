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
import nu.fgv.spexgram.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The maintenance jobs for ADMIN: how they are doing, and starting one by hand.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class MaintenanceService {

    private final MaintenanceJobs jobs;
    private final CallerService callerService;
    private final Environment environment;
    private final Executor executor;

    @Autowired
    public MaintenanceService(final MaintenanceJobs jobs, final CallerService callerService, final Environment environment) {
        this(jobs, callerService, environment, Executors.newVirtualThreadPerTaskExecutor());
    }

    MaintenanceService(final MaintenanceJobs jobs, final CallerService callerService, final Environment environment,
                       final Executor executor) {
        this.jobs = jobs;
        this.callerService = callerService;
        this.environment = environment;
        this.executor = executor;
    }

    /**
     * @return every maintenance job with its schedule and latest run
     */
    public List<MaintenanceJobStatus> statuses() {
        callerService.current().requireAdmin();

        return Arrays.stream(MaintenanceJob.values()).map(this::status).toList();
    }

    /**
     * Starts the job in the background, as its schedule would.
     *
     * @param job the job
     * @return false when it is already running, and so was not started again
     */
    public boolean run(final MaintenanceJob job) {
        callerService.current().requireAdmin();

        if (jobs.isRunning(job)) {
            return false;
        }
        log.info("ADMIN starting {}", job);
        executor.execute(() -> jobs.run(job));
        return true;
    }

    private MaintenanceJobStatus status(final MaintenanceJob job) {
        final var lastRun = jobs.lastRun(job);

        return new MaintenanceJobStatus(job, schedule(job), jobs.isRunning(job),
                lastRun.map(MaintenanceJobs.Run::startedAt).orElse(null),
                lastRun.map(MaintenanceJobs.Run::finishedAt).orElse(null),
                lastRun.map(MaintenanceJobs.Run::succeeded).orElse(null));
    }

    private @Nullable String schedule(final MaintenanceJob job) {
        final String cron = environment.getProperty(job.cronProperty());

        return cron == null || cron.isBlank() || "-".equals(cron.trim()) ? null : cron.trim();
    }

    /**
     * A maintenance job and how its latest run went.
     */
    public record MaintenanceJobStatus(MaintenanceJob job, @Nullable String schedule, boolean running,
                                       @Nullable Instant lastStartedAt, @Nullable Instant lastFinishedAt,
                                       @Nullable Boolean lastSucceeded) {
    }
}
