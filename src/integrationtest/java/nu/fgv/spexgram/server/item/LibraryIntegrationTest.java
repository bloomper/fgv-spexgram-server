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
package nu.fgv.spexgram.server.item;

import nu.fgv.spexgram.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The look of a photo library (5.1 of the spec): the timeline across albums by month with Immich's thumbhashes, the
 * favourites of each member, memories of spex whose premiere is near today, and the places and decades to explore.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class LibraryIntegrationTest extends AbstractIntegrationTest {

    private static final String TIMELINE = "query($f: Boolean) { timeline(favourites: $f) { month count } }";
    private static final String MONTH = """
            query($m: String, $f: Boolean) { timelineItems(month: $m, favourites: $f) {
                totalCount items { id takenAt thumbhash favourite album { id } }
            } }""";
    private static final String FAVOURITE = "mutation($ids: [ID!]!, $on: Boolean!) { favouritesSet(itemIds: $ids, favourite: $on) { id favourite } }";

    @TempDir
    private Path folder;

    private void taken(final String itemId, final String takenAt) {
        graphQl(EDITOR).document("mutation($id: ID!, $t: String) { itemUpdate(id: $id, input: {takenAt: $t}) { id } }")
                .variables(Map.of("id", itemId, "t", takenAt)).execute().errors().verify();
    }

    private void publish(final String itemId) {
        graphQl(EDITOR).document("mutation($ids: [ID!]!) { itemsUpdate(ids: $ids, input: {visibility: PUBLIC}) { skipped } }")
                .variable("ids", List.of(itemId)).execute().errors().verify();
    }

    @Test
    void theTimelineShowsEveryItemTheViewerMaySeeAcrossAlbumsByMonthNewestFirst() throws Exception {
        final String publicAlbum = createAlbum("PUBLIC");
        final String membersAlbum = createAlbum("MEMBERS");
        final List<String> shows = readyPhotos(publicAlbum, folder, "Premiären", "Finalen");
        final List<String> party = readyPhotos(membersAlbum, folder, "Festen");

        taken(shows.get(0), "1984-02-14T19:30:00");
        taken(shows.get(1), "1984-02-20T21:00:00");
        taken(party.getFirst(), "1984-05-01T23:00:00");
        publish(shows.get(0));
        publish(shows.get(1));

        graphQl(VIEWER).document(TIMELINE).execute()
                .path("timeline[*].month").entityList(String.class).containsExactly("1984-05", "1984-02")
                .path("timeline[*].count").entityList(Integer.class).containsExactly(1, 2);
        graphQl(null).document(TIMELINE).execute()
                .path("timeline[*].month").entityList(String.class).containsExactly("1984-02");
        graphQl(VIEWER).document(MONTH).variable("m", "1984-02").execute()
                .path("timelineItems.totalCount").entity(Integer.class).isEqualTo(2)
                .path("timelineItems.items[*].id").entityList(String.class).containsExactly(shows.get(1), shows.get(0))
                .path("timelineItems.items[0].album.id").entity(String.class).isEqualTo(publicAlbum)
                .path("timelineItems.items[0].thumbhash").entity(String.class).satisfies(hash -> assertThat(hash).isNotBlank());
        graphQl(null).document(MONTH).variable("m", "1984-05").execute()
                .path("timelineItems.totalCount").entity(Integer.class).isEqualTo(0);
        expectError(graphQl(VIEWER).document(MONTH).variable("m", "maj").execute(), "BAD_REQUEST");

        // An editor dates a scan by the day, keeping its time
        graphQl(EDITOR).document("mutation($ids: [ID!]!) { itemsUpdate(ids: $ids, input: {takenDate: \"1985-03-01\"}) { items { takenAt } } }")
                .variable("ids", List.of(party.getFirst())).execute()
                .path("itemsUpdate.items[0].takenAt").entity(String.class).isEqualTo("1985-03-01T23:00:00");
    }

    @Test
    void favouritesArePersonalAndOnlyForMembers() throws Exception {
        final String album = createAlbum("MEMBERS");
        final List<String> items = readyPhotos(album, folder, "Kören", "Orkestern");

        graphQl(VIEWER).document(FAVOURITE).variables(Map.of("ids", items.subList(0, 1), "on", true)).execute()
                .path("favouritesSet[*].favourite").entityList(Boolean.class).containsExactly(true);
        // Twice is still one favourite
        graphQl(VIEWER).document(FAVOURITE).variables(Map.of("ids", items.subList(0, 1), "on", true)).execute().errors().verify();

        graphQl(VIEWER).document(TIMELINE).variable("f", true).execute()
                .path("timeline[*].count").entityList(Integer.class).containsExactly(1);
        graphQl(VIEWER).document("query($id: ID!) { item(id: $id) { favourite } }").variable("id", items.getFirst()).execute()
                .path("item.favourite").entity(Boolean.class).isEqualTo(true);
        graphQl(EDITOR).document("query($id: ID!) { item(id: $id) { favourite } }").variable("id", items.getFirst()).execute()
                .path("item.favourite").entity(Boolean.class).isEqualTo(false);
        graphQl(EDITOR).document(TIMELINE).variable("f", true).execute()
                .path("timeline").entityList(Object.class).hasSize(0);
        expectError(graphQl(null).document(FAVOURITE).variables(Map.of("ids", items, "on", true)).execute(), "FORBIDDEN");
        expectError(graphQl(null).document(TIMELINE).variable("f", true).execute(), "FORBIDDEN");

        // A trashed favourite is not shown, and goes when the item is deleted
        graphQl(EDITOR).document("mutation($ids: [ID!]!) { itemsTrash(ids: $ids) { id } }").variable("ids", items.subList(0, 1)).execute().errors().verify();
        graphQl(VIEWER).document(TIMELINE).variable("f", true).execute().path("timeline").entityList(Object.class).hasSize(0);
        graphQl(ADMIN).document("mutation($ids: [ID!]!) { itemsDelete(ids: $ids) }").variable("ids", items.subList(0, 1)).execute().errors().verify();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM favourite").query(Integer.class).single()).isZero();

        graphQl(VIEWER).document(FAVOURITE).variables(Map.of("ids", items.subList(1, 2), "on", true)).execute().errors().verify();
        graphQl(VIEWER).document(FAVOURITE).variables(Map.of("ids", items.subList(1, 2), "on", false)).execute()
                .path("favouritesSet[*].favourite").entityList(Boolean.class).containsExactly(false);
    }

    @Test
    void albumsOfSpexWhosePremiereIsNearTodayAreRememberedAndPlacesAndDecadesAreExplored() throws Exception {
        final LocalDate premiere = LocalDate.now().minusYears(40).minusDays(2);

        WIREMOCK.stubFor(post(urlEqualTo("/api/graphql"))
                .withRequestBody(containing("SpexById"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"spex": {"id": "%s", "year": "%d", "title": "Cleopatra", "revival": false,
                                           "retired": false, "parent": null, "page": {"premiere": "%s"},
                                           "poster": {"thumbnailUrl": "https://spexipedia.test/media/images/p/thumbnail.webp"},
                                           "category": {"id": "00000000-0000-7000-8000-000000000002", "name": "Chalmersspexet"}}}}"""
                        .formatted(SPEX_ID, premiere.getYear(), premiere))));

        final String album = createAlbum("PUBLIC");

        readyPhotos(album, folder, "Premiären");
        graphQl(null).document("{ premiereAlbums { id spex { premiere posterUrl } } }").execute()
                .path("premiereAlbums[*].id").entityList(String.class).containsExactly(album)
                .path("premiereAlbums[0].spex[0].premiere").entity(String.class).isEqualTo(premiere.toString())
                .path("premiereAlbums[0].spex[0].posterUrl").entity(String.class).isEqualTo("https://spexipedia.test/media/images/p/thumbnail.webp");
        graphQl(null).document("{ premiereAlbums(days: 1) { id } }").execute()
                .path("premiereAlbums").entityList(Object.class).hasSize(0);

        graphQl(null).document("{ places { name albumCount } }").execute()
                .path("places[*].name").entityList(String.class).containsExactly("Chalmers kårhus")
                .path("places[0].albumCount").entity(Integer.class).isEqualTo(1);

        final int decade = premiere.getYear() / 10 * 10;

        graphQl(null).document("query($d: Int) { albums(filter: {decade: $d, place: \"chalmers KÅRHUS\"}) { totalCount } }")
                .variable("d", decade).execute()
                .path("albums.totalCount").entity(Integer.class).isEqualTo(1);
        graphQl(null).document("query($d: Int) { albums(filter: {decade: $d}) { totalCount } }")
                .variable("d", decade + 10).execute()
                .path("albums.totalCount").entity(Integer.class).isEqualTo(0);
    }
}
