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

package nu.fgv.spexgram.server.util.data;

import nu.fgv.spexgram.server.AbstractIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sample data (5.9 of the spec) is built on Spexipedia's spex and fgv-infra-deploy's sample users, by their fixed ids, and its
 * generated files go through Immich's real upload and processing.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SampleDataImporterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SampleDataImporter importer;

    private static void spexipediaWithSpex() {
        WIREMOCK.stubFor(post(urlEqualTo("/api/graphql")).withRequestBody(containing("AllSpex"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"spexAll": [
                            {"id": "00000000-0000-7000-8000-000000000001", "year": "1975", "title": "Nobel", "revival": false, "retired": false, "parent": null, "poster": null, "category": {"id": "00000000-0000-7000-8000-000000000002", "name": "Chalmersspexet"}},
                            {"id": "00000000-0000-7000-8000-000000000003", "year": "2024", "title": "Cleopatra", "revival": false, "retired": false, "parent": null, "poster": null, "category": {"id": "00000000-0000-7000-8000-000000000002", "name": "Chalmersspexet"}}
                        ]}}""")));
    }

    private long count(final String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    @Test
    @Order(2)
    void buildsAnArchiveOnSpexipediasSpexInImmichAndRecordsTheSampleUsers() throws Exception {
        spexipediaWithSpex();

        importer.importSampleData();

        // fgv-infra-deploy's sample users, recorded by their fixed ids without asking Keycloak
        assertThat(jdbcClient.sql("SELECT subject FROM app_user ORDER BY subject").query(String.class).list()).containsExactly(
                "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002",
                "00000000-0000-4000-8000-000000000003", "00000000-0000-4000-8000-000000000005");

        // 15 albums and a suggested one; 20 photos each, 4 videos (the text file is rejected), 6 unsorted, 5 contributions
        assertThat(count("SELECT COUNT(*) FROM album")).isEqualTo(16);
        assertThat(count("SELECT COUNT(*) FROM item")).isEqualTo(15 * 20 + 4 + 6 + 5);
        assertThat(count("SELECT COUNT(*) FROM item WHERE status = 'READY'")).isGreaterThan(300);
        assertThat(count("SELECT COUNT(*) FROM item WHERE failure = 'TOO_LONG' AND immich_asset_id IS NULL")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM item WHERE type = 'VIDEO'")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM item WHERE latitude IS NOT NULL")).isPositive();
        assertThat(count("SELECT COUNT(*) FROM item WHERE album_id IS NULL")).isEqualTo(6);
        assertThat(count("SELECT COUNT(*) FROM item WHERE contribution_status = 'WAITING'")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM album WHERE visibility = 'PUBLIC'")).isPositive();
        assertThat(count("SELECT COUNT(*) FROM album WHERE trashed_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM report WHERE visitor_email IS NOT NULL AND status = 'OPEN'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM album_suggestion WHERE status = 'OPEN'")).isEqualTo(1);

        // A visitor sees public albums, and the public item in the second album, which is for members
        graphQl(null).document("{ albums { totalCount } }").execute()
                .path("albums.totalCount").entity(Integer.class).satisfies(total -> assertThat(total).isPositive());
        final String membersAlbum = jdbcClient.sql("SELECT uuid FROM album ORDER BY id LIMIT 1 OFFSET 1").query(String.class).single();

        graphQl(null).document("query($id: ID!) { album(id: $id) { id } }").variable("id", membersAlbum).execute()
                .path("album").valueIsNull();
    }
}
