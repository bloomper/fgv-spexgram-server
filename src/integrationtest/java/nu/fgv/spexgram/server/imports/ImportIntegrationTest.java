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

package nu.fgv.spexgram.server.imports;

import nu.fgv.spexgram.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import API (spec 5.6.1): albums of a kind by alias and their photos, sent to Immich, unpublished, repeatable by
 * source key, and only for IMPORT.
 */
class ImportIntegrationTest extends AbstractIntegrationTest {

    private static final String MIGRATION = "fgv-content-migration";
    private static final String BATCH = """
            mutation($input: ImportBatchInput!, $dryRun: Boolean!) { importBatch(input: $input, dryRun: $dryRun) {
                created updated unchanged kept failed items { kind sourceKey id outcome message warnings }
            } }""";

    @TempDir
    private Path folder;

    private Map<String, String> upload(final Path file) throws Exception {
        final byte[] bytes = Files.readAllBytes(file);
        final String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        http().put().uri("/api/import/files/" + sha).header("Authorization", serviceToken(MIGRATION)).body(bytes).retrieve().toBodilessEntity();
        return Map.of("sha256", sha, "name", file.getFileName().toString());
    }

    private GraphQlTester.Response batch(final Map<String, Object> input, final boolean dryRun) {
        return graphQlWithToken(serviceToken(MIGRATION)).document(BATCH).variable("input", input).variable("dryRun", dryRun).execute();
    }

    @Test
    void onlyImportReachesTheImportApi() {
        expectError(graphQl(ADMIN).document(BATCH).variable("input", Map.of()).variable("dryRun", false).execute(), "FORBIDDEN");
    }

    @Test
    void albumsAndPhotosArriveUnpublishedInImmich() throws Exception {
        final Map<String, Object> input = Map.of(
                "albums", List.of(Map.of("sourceKey", "test:album", "spexIds", List.of(SPEX_ID), "kind", "rephelg",
                        "title", "Rephelg 3", "dateFrom", "2022-04-02")),
                "items", List.of(Map.of("sourceKey", "test:album/1", "albumSourceKey", "test:album",
                        "file", upload(photo(folder, "IMG_9338.jpg")), "photographer", "Okänd")));

        batch(input, true).path("importBatch.created").entity(Integer.class).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM item").query(Integer.class).single()).isZero();

        final List<String> ids = batch(input, false).path("importBatch.created").entity(Integer.class).isEqualTo(2)
                .path("importBatch.items[*].id").entityList(String.class).get();

        awaitReady(List.of(ids.get(1)));
        graphQl(EDITOR).document("query($id: ID!) { album(id: $id) { visibility kind { nameSv } } }").variable("id", ids.get(0)).execute()
                .path("album.visibility").entity(String.class).isEqualTo("UNPUBLISHED")
                .path("album.kind.nameSv").entity(String.class).isEqualTo("Repetition");
        assertThat(jdbcClient.sql("SELECT visibility FROM item").query(String.class).single()).isEqualTo("UNPUBLISHED");
        assertThat(jdbcClient.sql("SELECT photographer FROM item").query(String.class).single()).isEqualTo("Okänd");

        batch(input, false).path("importBatch.unchanged").entity(Integer.class).isEqualTo(2);
        graphQlWithToken(serviceToken(MIGRATION)).document("mutation { importForget(sourcePrefix: \"test:\") }").execute()
                .path("importForget").entity(Integer.class).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM album").query(Integer.class).single()).isZero();
    }
}
