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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uploads through tus to Immich (5.2 and 5.4 of the spec): processed until ready, duplicates recognised, files Immich
 * does not take rejected, and members' contributions waiting for review.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class UploadIntegrationTest extends AbstractIntegrationTest {

    private static final String ADD = """
            mutation($album: ID!, $uploads: [String!]!) {
                itemsAdd(albumId: $album, uploadIds: $uploads) { outcome message existingAlbumTitle item { id status } existingItem { id } }
            }""";

    @TempDir
    private Path folder;

    @Test
    void anUploadIsProcessedByImmichUntilItIsReady() throws Exception {
        final String album = createAlbum("MEMBERS");
        final String id = readyPhotos(album, folder, "Premiären").getFirst();

        graphQl(EDITOR)
                .document("query($id: ID!) { item(id: $id) { status type width height visibility uploadedBy originalFileName album { id } } }")
                .variable("id", id)
                .execute()
                .path("item.status").entity(String.class).isEqualTo("READY")
                .path("item.type").entity(String.class).isEqualTo("PHOTO")
                .path("item.width").entity(Integer.class).isEqualTo(640)
                .path("item.visibility").entity(String.class).isEqualTo("MEMBERS")
                .path("item.uploadedBy").entity(String.class).isEqualTo("Erik Test")
                .path("item.originalFileName").entity(String.class).isEqualTo("foto-0.jpg")
                .path("item.album.id").entity(String.class).isEqualTo(album);
    }

    @Test
    void aFileAlreadyInSpexgramIsNotAddedAgainAndTheEditorIsToldWhereItIs() throws Exception {
        final String album = createAlbum("MEMBERS");
        final Path photo = photo(folder, "kopia.jpg");
        final String first = graphQl(EDITOR).document(ADD).variables(Map.of("album", album, "uploads", List.of(upload(EDITOR, photo))))
                .execute()
                .path("itemsAdd[0].outcome").entity(String.class).isEqualTo("CREATED")
                .path("itemsAdd[0].item.id").entity(String.class).get();

        graphQl(EDITOR).document(ADD).variables(Map.of("album", createAlbum("MEMBERS"), "uploads", List.of(upload(EDITOR, photo))))
                .execute()
                .path("itemsAdd[0].outcome").entity(String.class).isEqualTo("DUPLICATE")
                .path("itemsAdd[0].existingItem.id").entity(String.class).isEqualTo(first)
                .path("itemsAdd[0].existingAlbumTitle").entity(String.class).isEqualTo("Cleopatra 2024 – föreställning");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM item").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aFileThatIsNotAPhotoOrVideoIsRejected() throws Exception {
        final String album = createAlbum("MEMBERS");
        final Path text = folder.resolve("inte-en-bild.txt");

        Files.writeString(text, "Det här är ingen bild.", StandardCharsets.UTF_8);
        graphQl(EDITOR).document(ADD).variables(Map.of("album", album, "uploads", List.of(upload(EDITOR, text))))
                .execute()
                .path("itemsAdd[0].outcome").entity(String.class).isEqualTo("REJECTED")
                .path("itemsAdd[0].message").entity(String.class).satisfies(message -> assertThat(message).contains("Unsupported"));
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM item").query(Long.class).single()).isZero();
    }

    @Test
    void aMembersContributionWaitsForReviewSeenOnlyByThemAndEditorsUntilAccepted() throws Exception {
        final String album = createAlbum("PUBLIC");
        final String id = graphQl(VIEWER)
                .document("mutation($album: ID!, $uploads: [String!]!) { contributionsAdd(albumId: $album, uploadIds: $uploads) { outcome item { id contributionStatus visibility photographer } } }")
                .variables(Map.of("album", album, "uploads", List.of(upload(VIEWER, photo(folder, "mitt-foto.jpg")))))
                .execute()
                .path("contributionsAdd[0].outcome").entity(String.class).isEqualTo("CREATED")
                .path("contributionsAdd[0].item.contributionStatus").entity(String.class).isEqualTo("WAITING")
                .path("contributionsAdd[0].item.visibility").entity(String.class).isEqualTo("UNPUBLISHED")
                .path("contributionsAdd[0].item.photographer").entity(String.class).isEqualTo("Lisa Test")
                .path("contributionsAdd[0].item.id").entity(String.class).get();

        awaitReady(List.of(id));
        graphQl(VIEWER).document("{ myContributions { id contributionStatus } }").execute()
                .path("myContributions[0].id").entity(String.class).isEqualTo(id);
        graphQl(null).document("query($id: ID!) { item(id: $id) { id } }").variable("id", id).execute().path("item").valueIsNull();
        graphQl(EDITOR).document("{ contributions { id } }").execute().path("contributions[*].id").entityList(String.class).containsExactly(id);

        graphQl(EDITOR)
                .document("mutation($ids: [ID!]!) { contributionsReview(itemIds: $ids, accept: true, message: \"Tack!\") { accepted itemId } }")
                .variable("ids", List.of(id))
                .execute()
                .path("contributionsReview[0].accepted").entity(Boolean.class).isEqualTo(true);
        graphQl(VIEWER).document("query($id: ID!) { item(id: $id) { contributionStatus effectiveVisibility } }").variable("id", id).execute()
                .path("item.contributionStatus").entity(String.class).isEqualTo("ACCEPTED")
                .path("item.effectiveVisibility").entity(String.class).isEqualTo("MEMBERS");

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM contribution_review WHERE notified_at IS NULL").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void visitorsCannotContributeNorMembersToAnUnpublishedAlbum() throws Exception {
        final String unpublished = createAlbum("UNPUBLISHED");
        final String upload = upload(VIEWER, photo(folder, "foto.jpg"));

        expectError(graphQl(VIEWER)
                .document("mutation($album: ID!, $uploads: [String!]!) { contributionsAdd(albumId: $album, uploadIds: $uploads) { outcome } }")
                .variables(Map.of("album", unpublished, "uploads", List.of(upload)))
                .execute(), "NOT_FOUND");
        expectError(graphQl(NO_ACCESS)
                .document("mutation($album: ID!, $uploads: [String!]!) { contributionsAdd(albumId: $album, uploadIds: $uploads) { outcome } }")
                .variables(Map.of("album", unpublished, "uploads", List.of(upload)))
                .execute(), "FORBIDDEN");
    }
}
