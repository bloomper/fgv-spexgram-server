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
import nu.fgv.spexgram.server.immich.ImmichClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
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

    private static final String LOCATION = "+57.6890+011.9740/";

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
    void aDuplicateOfAnItemInAnAlbumTheContributorMayNotSeeDoesNotNameIt() throws Exception {
        final String hidden = createAlbum("UNPUBLISHED");
        final Path photo = photo(folder, "hemlig.jpg");

        graphQl(EDITOR).document(ADD).variables(Map.of("album", hidden, "uploads", List.of(upload(EDITOR, photo))))
                .execute().path("itemsAdd[0].outcome").entity(String.class).isEqualTo("CREATED");
        graphQl(VIEWER)
                .document("mutation($album: ID!, $uploads: [String!]!) { contributionsAdd(albumId: $album, uploadIds: $uploads) { outcome existingAlbumTitle existingItem { id } } }")
                .variables(Map.of("album", createAlbum("MEMBERS"), "uploads", List.of(upload(VIEWER, photo))))
                .execute()
                .path("contributionsAdd[0].outcome").entity(String.class).isEqualTo("DUPLICATE")
                .path("contributionsAdd[0].existingAlbumTitle").valueIsNull()
                .path("contributionsAdd[0].existingItem").valueIsNull();
    }

    @Test
    void aVideoIsPlayedFromItsTranscodedVersionWithoutTheOriginalsLocation() throws Exception {
        final String album = createAlbum("MEMBERS");
        final Path video = folder.resolve("med-plats.mp4");
        final Process ffmpeg = new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=25:duration=3",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=22050:duration=3", "-shortest",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-metadata", "location=" + LOCATION, "-metadata", "creation_time=2024-02-14T20:00:00Z",
                video.toString()).redirectErrorStream(true).start();

        assertThat(ffmpeg.waitFor()).as(new String(ffmpeg.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isZero();
        assertThat(tags(video)).as("the original's metadata").contains("location=" + LOCATION, "creation_time=2024-02-14");

        final String id = graphQl(EDITOR).document(ADD).variables(Map.of("album", album, "uploads", List.of(upload(EDITOR, video))))
                .execute()
                .path("itemsAdd[0].item.id").entity(String.class).get();

        awaitReady(List.of(id));
        graphQl(VIEWER).document("query($id: ID!) { item(id: $id) { type media { videoUrl originalUrl } } }").variable("id", id).execute()
                .path("item.type").entity(String.class).isEqualTo("VIDEO")
                .path("item.media.videoUrl").entity(String.class).matches(url -> url.contains("/video/"))
                .path("item.media.originalUrl").valueIsNull();

        // What the media server serves for "video": Immich's playback endpoint, with the media server's read-only key
        final String asset = jdbcClient.sql("SELECT immich_asset_id FROM item WHERE uuid = :id").param("id", id).query(String.class).single();
        final byte[] played = RestClient.builder().baseUrl(immichUrl())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
                .build().get().uri("/api/assets/{id}/video/playback", asset)
                .header(ImmichClient.API_KEY_HEADER, Files.readString(DATA.resolve("immich-keys/media.key")))
                .retrieve().body(byte[].class);

        final Path playedFile = folder.resolve("spelad.mp4");

        Files.write(playedFile, played);
        assertThat(tags(playedFile)).as("the played video's metadata").doesNotContain("location", "2024-02-14");
    }

    /**
     * The container's metadata as ffprobe reads it, one key=value per line.
     */
    private static String tags(final Path file) throws Exception {
        final Process ffprobe = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "default=noprint_wrappers=1",
                file.toString()).redirectErrorStream(true).start();
        final String output = new String(ffprobe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(ffprobe.waitFor()).as(output).isZero();
        return output.replace("TAG:", "");
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
