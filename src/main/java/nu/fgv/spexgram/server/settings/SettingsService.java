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


package nu.fgv.spexgram.server.settings;

import nu.fgv.spexgram.server.audit.AuditService;
import nu.fgv.spexgram.server.user.Caller;
import nu.fgv.spexgram.server.user.CallerService;
import nu.fgv.spexgram.server.util.Locales;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.Locale;

/**
 * Reads and changes the settings. Everyone reads the start page; only admins read and change the rest.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class SettingsService {

    static final int MAX_UPLOAD_GB = 50;
    static final int MAX_VIDEO_MINUTES = 120;
    static final int MAX_CONTRIBUTION_ITEMS = 10_000;
    static final int MAX_CONTRIBUTION_GB = 500;

    private final SettingsRepository repository;
    private final CallerService callerService;
    private final AuditService auditService;
    private final Clock clock;

    public SettingsService(final SettingsRepository repository, final CallerService callerService,
                           final AuditService auditService, final Clock clock) {
        this.repository = repository;
        this.callerService = callerService;
        this.auditService = auditService;
        this.clock = clock;
    }

    private static @Nullable String text(final @Nullable String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static boolean outside(final int value, final int max) {
        return value < 1 || value > max;
    }

    static SettingsDto toDto(final Settings settings) {
        return new SettingsDto(settings.getWelcomeSv(), settings.getWelcomeEn(), settings.getPublicWelcomeSv(),
                settings.getPublicWelcomeEn(), settings.getContactEmail(), settings.getMaxUploadGb(),
                settings.getMaxVideoMinutes(), settings.getContributionMaxItems(), settings.getContributionMaxGb(),
                settings.isUserDownloadsOriginals(), settings.isShowPositions());
    }

    /**
     * The settings, without checking the caller, for the other services.
     */
    @Transactional(readOnly = true)
    public Settings current() {
        return repository.findById(Settings.ID).orElseGet(Settings::new);
    }

    /**
     * The start page's text in the viewer's language, the public one for visitors, and the contact address.
     */
    @Transactional(readOnly = true)
    public StartPageDto startPage(final boolean member, final @Nullable Locale locale) {
        final Settings settings = current();
        final boolean english = Locales.supported(locale).equals(Locale.ENGLISH);
        final String welcome = member
                ? english ? settings.getWelcomeEn() : settings.getWelcomeSv()
                : english ? settings.getPublicWelcomeEn() : settings.getPublicWelcomeSv();

        return new StartPageDto(welcome, settings.getContactEmail());
    }

    @Transactional(readOnly = true)
    public SettingsDto read() {
        callerService.current().requireAdmin();
        return toDto(current());
    }

    @Transactional
    public SettingsDto update(final SettingsDto input) {
        final Caller caller = callerService.current();

        caller.requireAdmin();
        if (outside(input.maxUploadGb(), MAX_UPLOAD_GB) || outside(input.maxVideoMinutes(), MAX_VIDEO_MINUTES)
                || outside(input.contributionMaxItems(), MAX_CONTRIBUTION_ITEMS)
                || outside(input.contributionMaxGb(), MAX_CONTRIBUTION_GB)
                || (input.contactEmail() != null && !input.contactEmail().isBlank() && !input.contactEmail().contains("@"))) {
            throw new BadRequestException("error.settings.invalid");
        }

        final Settings settings = repository.findById(Settings.ID).orElseGet(Settings::new);

        settings.setWelcomeSv(text(input.welcomeSv()));
        settings.setWelcomeEn(text(input.welcomeEn()));
        settings.setPublicWelcomeSv(text(input.publicWelcomeSv()));
        settings.setPublicWelcomeEn(text(input.publicWelcomeEn()));
        settings.setContactEmail(text(input.contactEmail()));
        settings.setMaxUploadGb(input.maxUploadGb());
        settings.setMaxVideoMinutes(input.maxVideoMinutes());
        settings.setContributionMaxItems(input.contributionMaxItems());
        settings.setContributionMaxGb(input.contributionMaxGb());
        settings.setUserDownloadsOriginals(input.userDownloadsOriginals());
        settings.setShowPositions(input.showPositions());
        settings.setLastModifiedBy(caller.subject());
        settings.setLastModifiedAt(clock.instant());
        repository.save(settings);
        auditService.record("UPDATE", "SETTINGS", null, "Ändrade inställningarna");
        return toDto(settings);
    }

    /**
     * The settings as the GraphQL API shows and takes them.
     */
    public record SettingsDto(@Nullable String welcomeSv, @Nullable String welcomeEn, @Nullable String publicWelcomeSv,
                              @Nullable String publicWelcomeEn, @Nullable String contactEmail, int maxUploadGb,
                              int maxVideoMinutes, int contributionMaxItems, int contributionMaxGb,
                              boolean userDownloadsOriginals, boolean showPositions) {
    }

    /**
     * What the start page shows the viewer.
     */
    public record StartPageDto(@Nullable String welcome, @Nullable String contactEmail) {
    }
}
