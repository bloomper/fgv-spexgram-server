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

import nu.fgv.spexgram.server.TestCallers;
import nu.fgv.spexgram.server.TestClock;
import nu.fgv.spexgram.server.audit.AuditService;
import nu.fgv.spexgram.server.settings.SettingsService.SettingsDto;
import nu.fgv.spexgram.server.user.CallerService;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The settings admins keep, and their limits.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock
    private SettingsRepository repository;
    @Mock
    private CallerService callerService;
    @Mock
    private AuditService auditService;
    private SettingsService service;

    @BeforeEach
    void setUp() {
        service = new SettingsService(repository, callerService, auditService, new TestClock(Instant.parse("2026-11-01T10:00:00Z")));
        lenient().when(repository.findById(Settings.ID)).thenReturn(Optional.empty());
    }

    private static SettingsDto settings(final int maxUploadGb) {
        return new SettingsDto(null, null, null, null, null, maxUploadGb, 10, 500, 20, false, true);
    }

    @Test
    void withoutSavedSettingsTheDefaultsApply() {
        when(callerService.current()).thenReturn(TestCallers.admin());

        assertThat(service.read()).isEqualTo(settings(4));
    }

    @Test
    void membersReadTheirStartPageAndVisitorsThePublicOne() {
        final Settings settings = new Settings();

        settings.setWelcomeSv("Hej medlem");
        settings.setWelcomeEn("Hello member");
        settings.setPublicWelcomeSv("Hej besökare");
        settings.setContactEmail("arkiv@fgv.nu");
        when(repository.findById(Settings.ID)).thenReturn(Optional.of(settings));

        assertThat(service.startPage(true, Locale.ENGLISH)).isEqualTo(new SettingsService.StartPageDto("Hello member", "arkiv@fgv.nu"));
        assertThat(service.startPage(false, null)).isEqualTo(new SettingsService.StartPageDto("Hej besökare", "arkiv@fgv.nu"));
    }

    @Test
    void anAdminSavesTrimmedTextsAndTheChangeIsAudited() {
        when(callerService.current()).thenReturn(TestCallers.admin());

        assertThat(service.update(new SettingsDto("  Välkommen!  ", " ", "Publik", null, " arkiv@fgv.nu ", 8, 10, 500, 20, true, false)))
                .isEqualTo(new SettingsDto("Välkommen!", null, "Publik", null, "arkiv@fgv.nu", 8, 10, 500, 20, true, false));
        verify(auditService).record("UPDATE", "SETTINGS", null, "Ändrade inställningarna");
    }

    @Test
    void limitsMustBeWithinTheirRanges() {
        when(callerService.current()).thenReturn(TestCallers.admin());

        assertThatThrownBy(() -> service.update(settings(0))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(settings(51))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.update(new SettingsDto(null, null, null, null, "no address", 4, 10, 500, 20, false, true)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void onlyAdminsReadAndChangeTheSettings() {
        when(callerService.current()).thenReturn(TestCallers.editor());

        assertThatThrownBy(() -> service.read()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.update(settings(8))).isInstanceOf(AccessDeniedException.class);
    }
}
