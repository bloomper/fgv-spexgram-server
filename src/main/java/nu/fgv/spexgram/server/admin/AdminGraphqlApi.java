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


package nu.fgv.spexgram.server.admin;

import nu.fgv.spexgram.server.audit.AuditEntry;
import nu.fgv.spexgram.server.audit.AuditFilter;
import nu.fgv.spexgram.server.audit.AuditService;
import nu.fgv.spexgram.server.immich.ImmichStatusService;
import nu.fgv.spexgram.server.immich.ImmichStatusService.ImmichStatusDto;
import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.item.ItemService;
import nu.fgv.spexgram.server.item.ItemViews;
import nu.fgv.spexgram.server.settings.Settings;
import nu.fgv.spexgram.server.settings.SettingsService;
import nu.fgv.spexgram.server.settings.SettingsService.SettingsDto;
import nu.fgv.spexgram.server.settings.SettingsService.StartPageDto;
import nu.fgv.spexgram.server.upload.UploadService;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.ViewerService;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * GraphQL for settings, the start page, limits, Immich's status and the audit log.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class AdminGraphqlApi {

    private final SettingsService settingsService;
    private final AuditService auditService;
    private final ImmichStatusService immichStatusService;
    private final ItemService itemService;
    private final UploadService uploadService;
    private final ViewerService viewerService;

    public AdminGraphqlApi(final SettingsService settingsService, final AuditService auditService,
                           final ImmichStatusService immichStatusService, final ItemService itemService,
                           final UploadService uploadService, final ViewerService viewerService) {
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.immichStatusService = immichStatusService;
        this.itemService = itemService;
        this.uploadService = uploadService;
        this.viewerService = viewerService;
    }

    @QueryMapping
    public StartPageDto startPage(final Locale locale) {
        return settingsService.startPage(viewerService.current().isMember(), locale);
    }

    @QueryMapping
    public LimitsDto limits() {
        final Viewer viewer = viewerService.current();
        final Settings settings = settingsService.current();

        return new LimitsDto(uploadService.maxSize(), settings.getMaxVideoMinutes(), settings.getContributionMaxItems(),
                settings.getContributionMaxGb(), ItemViews.canDownloadOriginals(viewer, settings));
    }

    @QueryMapping
    public SettingsDto settings() {
        return settingsService.read();
    }

    @MutationMapping
    public SettingsDto settingsUpdate(@Argument final SettingsDto input) {
        return settingsService.update(input);
    }

    @QueryMapping
    public ImmichStatusDto immichStatus() {
        return immichStatusService.status();
    }

    @SchemaMapping(typeName = "ImmichStatus", field = "failedItems")
    public List<ItemDto> failedItems(final ImmichStatusDto status) {
        return itemService.failed();
    }

    @QueryMapping
    public AuditPageDto auditLog(@Argument final @Nullable Integer page, @Argument final @Nullable Integer size,
                                 @Argument final @Nullable AuditFilter filter) {
        final int p = page == null ? 0 : page;
        final int s = size == null ? 50 : size;
        final Page<AuditEntry> entries = auditService.page(p, s, filter);

        return new AuditPageDto(entries.getContent().stream()
                .map(entry -> new AuditLogEntryDto(entry.getId(), entry.getAt(), entry.getActorSubject(), entry.getActorName(),
                        entry.getAction(), entry.getEntityType(), entry.getEntityId(), entry.getSummary()))
                .toList(), entries.getTotalElements(), p, s);
    }

    @QueryMapping
    public List<AuditActorDto> auditActors() {
        return auditService.actors().entrySet().stream().map(actor -> new AuditActorDto(actor.getKey(), actor.getValue())).toList();
    }

    public record LimitsDto(long maxUploadBytes, int maxVideoMinutes, int contributionMaxItems, int contributionMaxGb,
                            boolean canDownloadOriginals) {
    }

    public record AuditPageDto(List<AuditLogEntryDto> entries, long totalCount, int page, int size) {
    }

    public record AuditLogEntryDto(Long id, Instant at, String actorId, @Nullable String actorName, String action,
                                   String entityType, @Nullable String entityId, String summary) {
    }

    public record AuditActorDto(String id, @Nullable String name) {
    }
}
