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

package nu.fgv.spexgram.server.spex;

import nu.fgv.spexgram.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spexgram and Spexipedia: its notifications and the change feed. (What Spexipedia reads with {@code SPEXIPEDIA} is
 * tested with visibility.)
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class SpexipediaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SpexipediaSync sync;

    @Test
    void spexipediaNotifiesWithItsServiceAccountOnly() {
        notify(serviceToken("fgv-spexipedia-service")).expectStatus().isAccepted();
        notify(token(ADMIN)).expectStatus().isForbidden();
        notify(null).expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec notify(final String token) {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + localPort).build()
                .post().uri("/api/sync/notify")
                .headers(headers -> {
                    if (token != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, token);
                    }
                })
                .exchange();
    }

    @Test
    void theFeedKeepsTheCopiesCurrentAndMovesMergedReferences() {
        final String albumId = createAlbum("PUBLIC");
        final UUID spex = UUID.fromString(SPEX_ID);
        final UUID category = UUID.fromString("00000000-0000-7000-8000-000000000002");
        final UUID other = UUID.fromString("00000000-0000-7000-8000-000000000005");

        sync.applyAll(List.of(
                new FeedChange("1", "SPEX_CATEGORY", category, "UPSERT", null, new FeedChange.SpexCategory(category, "Chalmersspexet!"), null),
                new FeedChange("2", "SPEX", spex, "UPSERT", null, null, new FeedChange.Spex(spex, "2025", "Kleopatra", category, null, false))));

        graphQl(EDITOR).document("query($id: ID!) { album(id: $id) { spex { title year categoryName } } }")
                .variable("id", albumId).execute()
                .path("album.spex[0].title").entity(String.class).isEqualTo("Kleopatra")
                .path("album.spex[0].year").entity(Integer.class).isEqualTo(2025)
                .path("album.spex[0].categoryName").entity(String.class).isEqualTo("Chalmersspexet!");

        sync.applyAll(List.of(new FeedChange("3", "SPEX", spex, "MERGE", other, null, null)));
        assertThat(jdbcClient.sql("SELECT spex_id FROM album_spex").query(String.class).single()).isEqualTo(other.toString());

        // An album about both spex names the surviving one once, and the positions close up
        final UUID third = UUID.fromString("00000000-0000-7000-8000-000000000006");
        final UUID duplicate = UUID.fromString("00000000-0000-7000-8000-000000000007");

        jdbcClient.sql("""
                        INSERT INTO album_spex (album_id, position, spex_id, title)
                        SELECT album_id, position + 1, :duplicate, 'Dubblett' FROM album_spex WHERE spex_id = :other""")
                .param("duplicate", duplicate.toString()).param("other", other.toString()).update();
        jdbcClient.sql("""
                        INSERT INTO album_spex (album_id, position, spex_id, title)
                        SELECT album_id, position + 2, :third, 'Tredje' FROM album_spex WHERE spex_id = :other""")
                .param("third", third.toString()).param("other", other.toString()).update();
        sync.applyAll(List.of(new FeedChange("9", "SPEX", duplicate, "MERGE", other, null, null)));
        assertThat(jdbcClient.sql("SELECT spex_id FROM album_spex ORDER BY position").query(String.class).list())
                .containsExactly(other.toString(), third.toString());
        assertThat(jdbcClient.sql("SELECT MAX(position) - MIN(position) FROM album_spex").query(Integer.class).single()).isEqualTo(1);
    }
}
