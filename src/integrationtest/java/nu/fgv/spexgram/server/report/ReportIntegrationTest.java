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

import nu.fgv.spexgram.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reports (5.5 of the spec): a visitor reports a public item with an email address and a Turnstile token, which hides
 * it from visitors at once; editors are emailed; handling it shows it again and emails the visitor.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ReportIntegrationTest extends AbstractIntegrationTest {

    private static final String REPORT = """
            mutation($input: ReportInput!) { reportCreate(input: $input) { id status byVisitor } }""";

    @TempDir
    private Path folder;

    private static List<String> recipients() {
        return Arrays.stream(MAIL.getReceivedMessages()).map(message -> {
            try {
                return message.getAllRecipients()[0].toString();
            } catch (final jakarta.mail.MessagingException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    @Test
    void aVisitorsReportHidesThePublicItemFromVisitorsUntilItIsHandled() throws Exception {
        final String album = createAlbum("PUBLIC");
        final String item = readyPhotos(album, folder, "Jag på scenen").getFirst();

        graphQl(EDITOR)
                .document("mutation($ids: [ID!]!) { itemsUpdate(ids: $ids, input: {visibility: PUBLIC}) { id } }")
                .variable("ids", List.of(item)).execute().errors().verify();
        graphQl(null).document("query($id: ID!) { item(id: $id) { id } }").variable("id", item).execute()
                .path("item.id").entity(String.class).isEqualTo(item);

        final String report = graphQl(null).document(REPORT)
                .variable("input", Map.of("itemId", item, "reason", "I_AM_IN_IT", "message", "Ta bort mig",
                        "email", "besokare@example.org", "turnstileToken", "ok"))
                .execute()
                .path("reportCreate.byVisitor").entity(Boolean.class).isEqualTo(true)
                .path("reportCreate.id").entity(String.class).get();

        graphQl(null).document("query($id: ID!) { item(id: $id) { id } }").variable("id", item).execute().path("item").valueIsNull();
        graphQl(VIEWER).document("query($id: ID!) { item(id: $id) { visibility } }").variable("id", item).execute()
                .path("item.visibility").entity(String.class).isEqualTo("MEMBERS");
        assertThat(MAIL.waitForIncomingEmail(10_000, 1)).isTrue();
        assertThat(recipients()).contains(EDITOR);
        graphQl(EDITOR).document("{ reports { id reporter item { id } } }").execute()
                .path("reports[0].reporter").entity(String.class).isEqualTo("besokare@example.org");

        graphQl(EDITOR)
                .document("mutation($id: ID!) { reportHandle(id: $id, outcome: KEPT, message: \"Bilden är från en publik föreställning.\") { status outcome } }")
                .variable("id", report)
                .execute()
                .path("reportHandle.status").entity(String.class).isEqualTo("HANDLED");
        graphQl(null).document("query($id: ID!) { item(id: $id) { visibility } }").variable("id", item).execute()
                .path("item.visibility").entity(String.class).isEqualTo("PUBLIC");
        assertThat(MAIL.waitForIncomingEmail(10_000, 2)).isTrue();
        assertThat(recipients()).contains("besokare@example.org");
    }

    @Test
    void aVisitorWithoutAPassingTurnstileOrAnAddressCannotReport() throws Exception {
        final String album = createAlbum("PUBLIC");
        final String item = readyPhotos(album, folder, "Publik bild").getFirst();

        graphQl(EDITOR).document("mutation($ids: [ID!]!) { itemsUpdate(ids: $ids, input: {visibility: PUBLIC}) { id } }")
                .variable("ids", List.of(item)).execute().errors().verify();
        expectError(graphQl(null).document(REPORT)
                .variable("input", Map.of("itemId", item, "reason", "OTHER", "email", "besokare@example.org", "turnstileToken", "bad"))
                .execute(), "BAD_REQUEST");
        expectError(graphQl(null).document(REPORT)
                .variable("input", Map.of("itemId", item, "reason", "OTHER", "turnstileToken", "ok"))
                .execute(), "BAD_REQUEST");
    }

    @Test
    void aMembersReportUnpublishesTheItemAndRemovingTrashesIt() throws Exception {
        final String album = createAlbum("MEMBERS");
        final String item = readyPhotos(album, folder, "Medlemsbild").getFirst();
        final String report = graphQl(VIEWER).document(REPORT)
                .variable("input", Map.of("itemId", item, "reason", "I_AM_IN_IT"))
                .execute()
                .path("reportCreate.byVisitor").entity(Boolean.class).isEqualTo(false)
                .path("reportCreate.id").entity(String.class).get();

        graphQl(VIEWER).document("query($id: ID!) { item(id: $id) { id } }").variable("id", item).execute().path("item").valueIsNull();
        graphQl(VIEWER).document("{ reports { id status } }").execute().path("reports[0].id").entity(String.class).isEqualTo(report);
        graphQl(EDITOR).document("mutation($id: ID!) { reportHandle(id: $id, outcome: REMOVED) { outcome } }").variable("id", report)
                .execute().path("reportHandle.outcome").entity(String.class).isEqualTo("REMOVED");
        graphQl(EDITOR).document("{ trash { items { id } } }").execute().path("trash.items[*].id").entityList(String.class).containsExactly(item);
        // Without open, every report: the member's own handled one, in one call
        graphQl(VIEWER).document("{ reports(mine: true) { id status } }").execute()
                .path("reports[*].id").entityList(String.class).containsExactly(report)
                .path("reports[0].status").entity(String.class).isEqualTo("HANDLED");
        graphQl(VIEWER).document("{ reports(open: true) { id } }").execute().path("reports").entityList(Object.class).hasSize(0);
        graphQl(EDITOR).document("{ reports(mine: true) { id } }").execute().path("reports").entityList(Object.class).hasSize(0);
        graphQl(EDITOR).document("{ reports(open: false) { id } }").execute()
                .path("reports[*].id").entityList(String.class).containsExactly(report);
    }
}
