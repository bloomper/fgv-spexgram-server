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


package nu.fgv.spexgram.server.report;

import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.item.ItemService;
import nu.fgv.spexgram.server.report.ReportService.ReportDto;
import nu.fgv.spexgram.server.report.ReportService.ReportInput;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * GraphQL for reports. A thin adapter: {@link ReportService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ReportGraphqlApi {

    private final ReportService service;
    private final ItemService itemService;

    public ReportGraphqlApi(final ReportService service, final ItemService itemService) {
        this.service = service;
        this.itemService = itemService;
    }

    @QueryMapping
    public List<ReportDto> reports(@Argument final @Nullable Boolean open, @Argument final @Nullable Boolean mine) {
        return service.reports(open == null || open, Boolean.TRUE.equals(mine));
    }

    @SchemaMapping(typeName = "Report", field = "item")
    public @Nullable ItemDto item(final ReportDto report) {
        return report.itemId() == null ? null : itemService.item(report.itemId());
    }

    @MutationMapping
    public ReportDto reportCreate(@Argument final ReportInput input, final Locale locale) {
        return service.create(input, locale);
    }

    @MutationMapping
    public ReportDto reportHandle(@Argument final UUID id, @Argument final Report.Outcome outcome, @Argument final @Nullable String message) {
        return service.handle(id, outcome, message);
    }
}
