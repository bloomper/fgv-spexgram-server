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

/**
 * The scheduled maintenance jobs, which ADMIN can also start by hand. Their schedules are set in the
 * {@code spexgram.jobs} properties and run in {@code spexgram.time-zone}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum MaintenanceJob {

    SPEX_REFRESH("spexgram.jobs.spex-refresh.cron-expression"),
    UNSORTED_SCAN("spexgram.jobs.unsorted-scan.cron-expression"),
    PROCESSING_POLL("spexgram.jobs.processing-poll.cron-expression"),
    ITEM_CHECK("spexgram.jobs.item-check.cron-expression"),
    UPLOAD_CLEANUP("spexgram.jobs.upload-cleanup.cron-expression"),
    RETENTION("spexgram.jobs.retention.cron-expression"),
    CONTRIBUTION_MAIL("spexgram.jobs.contribution-mail.cron-expression");

    private final String cronProperty;

    MaintenanceJob(final String cronProperty) {
        this.cronProperty = cronProperty;
    }

    /**
     * @return the name of the property holding the job's cron expression
     */
    public String cronProperty() {
        return cronProperty;
    }
}
