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

import nu.fgv.spexgram.server.item.Item;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hiding a reported item at once: a visitor's report takes it off the public side, a member's from the members.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ReportServiceTest {

    private static Item item(final Visibility visibility) {
        final Item item = new Item();

        item.setVisibility(visibility);
        return item;
    }

    @Test
    void aVisitorsReportMakesAPublicItemAMembersItem() {
        final Item item = item(Visibility.PUBLIC);

        assertThat(ReportService.hide(item, true)).isEqualTo(Visibility.PUBLIC);
        assertThat(item.getVisibility()).isEqualTo(Visibility.MEMBERS);
    }

    @Test
    void aVisitorCannotHideAnythingFromMembers() {
        final Item item = item(Visibility.MEMBERS);

        assertThat(ReportService.hide(item, true)).isNull();
        assertThat(item.getVisibility()).isEqualTo(Visibility.MEMBERS);
    }

    @Test
    void aMembersReportUnpublishesTheItem() {
        final Item item = item(Visibility.PUBLIC);

        assertThat(ReportService.hide(item, false)).isEqualTo(Visibility.PUBLIC);
        assertThat(item.getVisibility()).isEqualTo(Visibility.UNPUBLISHED);
    }

    private static Report report(final Item item, final boolean visitor, final String at) {
        final Report report = new Report();

        report.setItem(item);
        report.setVisitorEmail(visitor ? "besok@example.org" : null);
        report.setCreatedAt(Instant.parse(at));
        report.setHiddenFrom(ReportService.hide(item, visitor));
        return report;
    }

    @Test
    void keepingShowsTheItemAgainAsBefore() {
        final Item item = item(Visibility.PUBLIC);
        final Report report = report(item, false, "2026-11-01T10:00:00Z");

        ReportService.showAgain(report, item, List.of());

        assertThat(item.getVisibility()).isEqualTo(Visibility.PUBLIC);
    }

    @Test
    void anItemAnEditorChangedSinceTheReportHidItIsLeftAsItIs() {
        final Item item = item(Visibility.PUBLIC);
        final Report report = report(item, true, "2026-11-01T10:00:00Z");

        item.setVisibility(Visibility.UNPUBLISHED);
        ReportService.showAgain(report, item, List.of());

        assertThat(item.getVisibility()).isEqualTo(Visibility.UNPUBLISHED);
    }

    @Test
    void anotherOpenReportKeepsHidingTheItemWhicheverIsHandledFirst() {
        final Item first = item(Visibility.PUBLIC);
        final Report visitors = report(first, true, "2026-11-01T10:00:00Z");
        final Report members = report(first, false, "2026-11-02T10:00:00Z");

        // The later, member's, report handled first: back to what the visitor's report left
        ReportService.showAgain(members, first, List.of(visitors));
        assertThat(first.getVisibility()).isEqualTo(Visibility.MEMBERS);
        ReportService.showAgain(visitors, first, List.of());
        assertThat(first.getVisibility()).isEqualTo(Visibility.PUBLIC);

        final Item second = item(Visibility.PUBLIC);
        final Report visitors2 = report(second, true, "2026-11-01T10:00:00Z");
        final Report members2 = report(second, false, "2026-11-02T10:00:00Z");

        // The earlier one handled first: the later one still hides it, and learns what it was before both
        ReportService.showAgain(visitors2, second, List.of(members2));
        assertThat(second.getVisibility()).isEqualTo(Visibility.UNPUBLISHED);
        ReportService.showAgain(members2, second, List.of());
        assertThat(second.getVisibility()).isEqualTo(Visibility.PUBLIC);
    }
}
