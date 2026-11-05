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


package nu.fgv.spexgram.server.contribution;

import nu.fgv.spexgram.server.item.ItemRepository;
import nu.fgv.spexgram.server.settings.Settings;
import nu.fgv.spexgram.server.settings.SettingsService;
import nu.fgv.spexgram.server.upload.UploadSessionRepository;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * How much a member may contribute per day (5.4): at most {@code contributionMaxItems} items and
 * {@code contributionMaxGb} GB, counted from midnight, uploads in progress included.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class ContributionQuota {

    private static final long GB = 1024L * 1024 * 1024;

    private final ItemRepository itemRepository;
    private final UploadSessionRepository uploadRepository;
    private final SettingsService settingsService;
    private final Clock clock;

    public ContributionQuota(final ItemRepository itemRepository, final UploadSessionRepository uploadRepository,
                             final SettingsService settingsService, final Clock clock) {
        this.itemRepository = itemRepository;
        this.uploadRepository = uploadRepository;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    private Instant today() {
        return LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
    }

    /**
     * Refuses an upload of this many bytes when it would take the member past today's limit.
     */
    @Transactional(readOnly = true)
    public void requireBytes(final String subject, final long bytes) {
        final Settings settings = settingsService.current();
        final Instant today = today();
        final long used = itemRepository.sumContributionBytesSince(subject, today) + uploadRepository.sumLengthByOwnerSince(subject, today);

        if (used + bytes > settings.getContributionMaxGb() * GB) {
            throw new BadRequestException("error.contribution.limitBytes", settings.getContributionMaxGb());
        }
    }

    /**
     * Refuses this many more contributions when they would take the member past today's limit.
     */
    @Transactional(readOnly = true)
    public void requireItems(final String subject, final int count) {
        final Settings settings = settingsService.current();

        if (itemRepository.countContributionsSince(subject, today()) + count > settings.getContributionMaxItems()) {
            throw new BadRequestException("error.contribution.limitItems", settings.getContributionMaxItems());
        }
    }
}
