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


package nu.fgv.spexgram.server.visibility;

import nu.fgv.spexgram.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who sees what (4.1 and 5.10 of the spec): visitors only public albums and items, a public item in an album for
 * members by its address but not the album, members more, editors everything.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class VisibilityIntegrationTest extends AbstractIntegrationTest {

    private static final String MEDIA = "^https://media\\.spexgram\\.test/a/[0-9a-f-]{36}/%s/\\d+/[A-Za-z0-9_-]{22}$";

    @TempDir
    private Path folder;

    private void setItemVisibility(final String itemId, final String visibility) {
        graphQl(EDITOR)
                .document("mutation($ids: [ID!]!, $v: Visibility) { itemsUpdate(ids: $ids, input: {visibility: $v}) { visibility } }")
                .variables(Map.of("ids", List.of(itemId), "v", visibility))
                .execute()
                .errors().verify();
    }

    @Test
    void aVisitorSeesOnlyPublicAlbumsAndTheirPublicItems() throws Exception {
        final String publicAlbum = createAlbum("PUBLIC");
        final String membersAlbum = createAlbum("MEMBERS");
        final List<String> items = readyPhotos(publicAlbum, folder, "Kören på scenen", "Bakom kulisserna");

        readyPhotos(membersAlbum, folder, "Festen efteråt");
        setItemVisibility(items.getFirst(), "PUBLIC");

        graphQl(null)
                .document("{ albums { totalCount albums { id itemCount items { items { id visibility effectiveVisibility } } } } }")
                .execute()
                .path("albums.totalCount").entity(Integer.class).isEqualTo(1)
                .path("albums.albums[0].id").entity(String.class).isEqualTo(publicAlbum)
                .path("albums.albums[0].itemCount").entity(Integer.class).isEqualTo(1)
                .path("albums.albums[0].items.items[*].id").entityList(String.class).containsExactly(items.getFirst());
        graphQl(null).document("query($id: ID!) { album(id: $id) { id } }").variable("id", membersAlbum).execute()
                .path("album").valueIsNull();
        graphQl(null).document("query($id: ID!) { item(id: $id) { id } }").variable("id", items.get(1)).execute()
                .path("item").valueIsNull();
        graphQl(null)
                .document("{ search(q: \"kören\") { albums { id } items { id } } }")
                .execute()
                .path("search.items[*].id").entityList(String.class).containsExactly(items.getFirst());
        graphQl(null)
                .document("{ search(q: \"festen\") { items { id } } }")
                .execute()
                .path("search.items").entityList(Object.class).hasSize(0);
    }

    @Test
    void aPublicItemInAnAlbumForMembersIsReachableByIdButTheAlbumIsNotListed() throws Exception {
        final String album = createAlbum("MEMBERS");
        final List<String> items = readyPhotos(album, folder, "Gruppbild", "Repetition");

        setItemVisibility(items.getFirst(), "PUBLIC");

        graphQl(null)
                .document("query($id: ID!) { item(id: $id) { id album { id } uploadedBy latitude effectiveVisibility media { thumbnailUrl previewUrl originalUrl } } }")
                .variable("id", items.getFirst())
                .execute()
                .path("item.id").entity(String.class).isEqualTo(items.getFirst())
                .path("item.album").valueIsNull()
                .path("item.uploadedBy").valueIsNull()
                .path("item.effectiveVisibility").entity(String.class).isEqualTo("PUBLIC")
                .path("item.media.thumbnailUrl").entity(String.class).matches(url -> url.matches(MEDIA.formatted("thumbnail")))
                .path("item.media.previewUrl").entity(String.class).matches(url -> url.matches(MEDIA.formatted("preview")))
                .path("item.media.originalUrl").valueIsNull();
        graphQl(null).document("{ albums { totalCount } }").execute().path("albums.totalCount").entity(Integer.class).isEqualTo(0);
        graphQl(null).document("query($ids: [ID!]!) { publicItems(ids: $ids) { id } }").variable("ids", items).execute()
                .path("publicItems[*].id").entityList(String.class).containsExactly(items.getFirst());
        graphQl(null).document("query($id: ID!) { spexAlbums(spexId: $id) { id } }").variable("id", SPEX_ID).execute()
                .path("spexAlbums").entityList(Object.class).hasSize(0);
    }

    @Test
    void membersSeeMembersAlbumsAndEditorsAlsoUnpublishedOnes() throws Exception {
        final String members = createAlbum("MEMBERS");
        final String unpublished = createAlbum("UNPUBLISHED");
        final List<String> items = readyPhotos(members, folder, "Medlemsbild", "Opublicerad bild");

        setItemVisibility(items.get(1), "UNPUBLISHED");

        graphQl(VIEWER)
                .document("query($id: ID!) { albums { totalCount } album(id: $id) { itemCount items { items { id media { originalUrl } } } } }")
                .variable("id", members)
                .execute()
                .path("albums.totalCount").entity(Integer.class).isEqualTo(1)
                .path("album.itemCount").entity(Integer.class).isEqualTo(1)
                .path("album.items.items[0].id").entity(String.class).isEqualTo(items.getFirst())
                .path("album.items.items[0].media.originalUrl").valueIsNull();
        graphQl(VIEWER).document("query($id: ID!) { album(id: $id) { id } }").variable("id", unpublished).execute()
                .path("album").valueIsNull();
        graphQl(NO_ACCESS).document("{ albums { totalCount } }").execute()
                .path("albums.totalCount").entity(Integer.class).isEqualTo(0);
        graphQl(EDITOR)
                .document("query($id: ID!) { albums { totalCount } album(id: $id) { itemCount visibilityCounts { visibility count } items { items { media { originalUrl } } } } }")
                .variable("id", members)
                .execute()
                .path("albums.totalCount").entity(Integer.class).isEqualTo(2)
                .path("album.itemCount").entity(Integer.class).isEqualTo(2)
                .path("album.items.items[0].media.originalUrl").entity(String.class).matches(url -> url.matches(MEDIA.formatted("original")));
    }

    @Test
    void zipsAreForMembersWhoMayDownloadOriginals() throws Exception {
        final String album = createAlbum("MEMBERS");

        readyPhotos(album, folder, "Ett", "Två");

        final int forbidden = http().get().uri("/api/albums/{id}/zip", album).header("Authorization", token(VIEWER))
                .exchange((request, response) -> response.getStatusCode().value());
        final byte[] zip = http().get().uri("/api/albums/{id}/zip", album).header("Authorization", token(EDITOR))
                .retrieve().body(byte[].class);
        final int anonymous = http().get().uri("/api/albums/{id}/zip", album)
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(forbidden).isEqualTo(403);
        assertThat(anonymous).isEqualTo(401);
        assertThat(zip).isNotNull();
        assertThat(new String(zip, 0, 2, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PK");
    }
}
