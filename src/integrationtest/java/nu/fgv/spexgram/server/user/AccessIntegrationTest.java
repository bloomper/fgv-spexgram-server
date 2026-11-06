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


package nu.fgv.spexgram.server.user;

import nu.fgv.spexgram.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Who may call what: GraphQL answers without a token too, with only public data; roles on {@code fgv-spexgram}
 * decide the rest.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class AccessIntegrationTest extends AbstractIntegrationTest {

    @Test
    void meIsNullForVisitorsAndListsTheRolesOfOthers() {
        graphQl(null).document("{ me { username } }").execute().path("me").valueIsNull();
        graphQl(EDITOR)
                .document("{ me { username email roles } }")
                .execute()
                .path("me.username").entity(String.class).isEqualTo(EDITOR)
                .path("me.email").entity(String.class).isEqualTo(EDITOR)
                .path("me.roles").entityList(String.class).contains("USER", "EDITOR");
        graphQl(NO_ACCESS).document("{ me { roles } }").execute().path("me.roles").entityList(String.class).hasSize(0);
    }

    @Test
    void visitorsReadThePublicStartPageAndLimitsButNothingForMembers() {
        graphQl(ADMIN)
                .document("mutation { settingsUpdate(input: {welcomeSv: \"Hej\", welcomeEn: \"Hello members\", publicWelcomeEn: \"Hello visitors\", contactEmail: \"arkiv@fgv.nu\", maxUploadGb: 4, maxVideoMinutes: 10, contributionMaxItems: 500, contributionMaxGb: 20, userDownloadsOriginals: false, showPositions: true}) { maxUploadGb } }")
                .execute()
                .errors().verify();
        graphQl(null).document("{ startPage { welcome contactEmail } limits { canDownloadOriginals } }").execute()
                .path("startPage.welcome").entity(String.class).isEqualTo("Hello visitors")
                .path("startPage.contactEmail").entity(String.class).isEqualTo("arkiv@fgv.nu")
                .path("limits.canDownloadOriginals").entity(Boolean.class).isEqualTo(false);
        graphQl(VIEWER).document("{ startPage { welcome } }").execute().path("startPage.welcome").entity(String.class).isEqualTo("Hello members");
        expectError(graphQl(null).document("{ myContributions { id } }").execute(), "FORBIDDEN");
        expectError(graphQl(null).document("{ mapItems { id } }").execute(), "FORBIDDEN");
        expectError(graphQl(NO_ACCESS).document("{ smartSearch(q: \"scen\") { id } }").execute(), "FORBIDDEN");
    }

    @Test
    void albumSuggestionsAreTheMembersOwnAndEveryonesForEditorsUnlessMine() {
        final String suggestion = graphQl(VIEWER)
                .document("mutation { albumSuggest(input: {title: \"Kräftskivan\"}) { id album { id suggested } } }")
                .execute()
                .path("albumSuggest.album.suggested").entity(Boolean.class).isEqualTo(true)
                .path("albumSuggest.id").entity(String.class).get();

        graphQl(VIEWER).document("{ albumSuggestions { id status } }").execute()
                .path("albumSuggestions[*].id").entityList(String.class).containsExactly(suggestion);
        graphQl(EDITOR).document("{ all: albumSuggestions { id } open: albumSuggestions(open: true) { id } mine: albumSuggestions(mine: true) { id } }")
                .execute()
                .path("all[*].id").entityList(String.class).contains(suggestion)
                .path("open[*].id").entityList(String.class).contains(suggestion)
                .path("mine").entityList(Object.class).hasSize(0);
        graphQl(EDITOR).document("mutation($id: ID!) { albumSuggestionReview(id: $id, accept: true) { status } }").variable("id", suggestion)
                .execute().path("albumSuggestionReview.status").entity(String.class).isEqualTo("ACCEPTED");
        graphQl(VIEWER).document("{ handled: albumSuggestions(open: false) { id } open: albumSuggestions(open: true) { id } }").execute()
                .path("handled[*].id").entityList(String.class).containsExactly(suggestion)
                .path("open").entityList(Object.class).hasSize(0);
    }

    @Test
    void editingIsForEditorsAndSettingsForAdmins() {
        expectError(graphQl(VIEWER).document("mutation { albumCreate(input: {title: \"Fest\"}) { id } }").execute(), "FORBIDDEN");
        expectError(graphQl(null).document("mutation { albumCreate(input: {title: \"Fest\"}) { id } }").execute(), "FORBIDDEN");
        expectError(graphQl(EDITOR).document("{ settings { maxUploadGb } }").execute(), "FORBIDDEN");
        expectError(graphQl(EDITOR).document("{ immichStatus { available } }").execute(), "FORBIDDEN");
        graphQl(ADMIN).document("{ settings { maxUploadGb } immichStatus { available version queues { name } } }").execute()
                .path("settings.maxUploadGb").entity(Integer.class).isEqualTo(4)
                .path("immichStatus.available").entity(Boolean.class).isEqualTo(true)
                .path("immichStatus.version").entity(String.class).isEqualTo("v3.3.6");
    }

    @Test
    void anAlbumGetsItsDefaultTitleFromItsSpexAndKind() {
        graphQl(EDITOR)
                .document("mutation { albumCreate(input: {spexIds: [\"" + SPEX_ID + "\"], dateFrom: \"2024-02-14\"}) { title kind { nameEn isDefault } spex { title categoryName } } }")
                .execute()
                .path("albumCreate.title").entity(String.class).isEqualTo("Cleopatra 2024 – föreställning")
                .path("albumCreate.kind.nameEn").entity(String.class).isEqualTo("Show")
                .path("albumCreate.spex[0].categoryName").entity(String.class).isEqualTo("Chalmersspexet");
        graphQl(null).document("{ albumKinds { nameSv albumCount } }").execute()
                .path("albumKinds[0].nameSv").entity(String.class).isEqualTo("Föreställning");
    }
}
