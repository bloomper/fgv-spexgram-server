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
}
