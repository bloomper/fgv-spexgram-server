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

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anders Jacobsson
 * @since 1.0
 */
class MaintenanceJobsTest {

    @Test
    void recordsASuccessfulRun() {
        final AtomicInteger runs = new AtomicInteger();
        final MaintenanceJobs jobs = new MaintenanceJobs(Map.of(MaintenanceJob.SPEX_REFRESH, runs::incrementAndGet));

        assertThat(jobs.lastRun(MaintenanceJob.SPEX_REFRESH)).isEmpty();

        jobs.spexRefresh();

        assertThat(runs).hasValue(1);
        assertThat(jobs.lastRun(MaintenanceJob.SPEX_REFRESH)).hasValueSatisfying(run -> {
            assertThat(run.finishedAt()).isNotNull();
            assertThat(run.succeeded()).isTrue();
        });
    }

    @Test
    void recordsAFailedRunWithoutThrowing() {
        final MaintenanceJobs jobs = new MaintenanceJobs(Map.of(MaintenanceJob.UPLOAD_CLEANUP, () -> {
            throw new IllegalStateException("boom");
        }));

        jobs.uploadCleanup();

        assertThat(jobs.isRunning(MaintenanceJob.UPLOAD_CLEANUP)).isFalse();
        assertThat(jobs.lastRun(MaintenanceJob.UPLOAD_CLEANUP)).hasValueSatisfying(run -> assertThat(run.succeeded()).isFalse());
    }

    @Test
    void doesNotStartAJobThatIsRunning() throws InterruptedException {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final MaintenanceJobs jobs = new MaintenanceJobs(Map.of(MaintenanceJob.SPEX_REFRESH, () -> {
            runs.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        final Thread first = Thread.ofVirtual().start(() -> jobs.run(MaintenanceJob.SPEX_REFRESH));

        started.await();
        assertThat(jobs.isRunning(MaintenanceJob.SPEX_REFRESH)).isTrue();
        assertThat(jobs.run(MaintenanceJob.SPEX_REFRESH)).isFalse();

        release.countDown();
        first.join();

        assertThat(runs).hasValue(1);
        assertThat(jobs.isRunning(MaintenanceJob.SPEX_REFRESH)).isFalse();
    }
}
