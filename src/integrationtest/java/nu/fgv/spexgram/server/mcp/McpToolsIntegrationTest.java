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

package nu.fgv.spexgram.server.mcp;

import nu.fgv.spexgram.server.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP tools as a whole: public album listings only, read-only, and nothing but what visitors see, even for an
 * editor.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class McpToolsIntegrationTest extends AbstractIntegrationTest {

    private static final String MCP_PATH = "/api/mcp";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final AtomicInteger requestId = new AtomicInteger();

    @TempDir
    private Path folder;

    private static Map<String, Object> args(final Object... pairs) {
        final Map<String, Object> map = new HashMap<>();

        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static boolean refused(final JsonNode response) {
        return response.has("error") || response.path("result").path("isError").asBoolean(false);
    }

    private HttpStatusCode status(final String token, final String path, final String body) {
        return http().post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body(body)
                .exchange((request, response) -> response.getStatusCode());
    }

    private JsonNode rpc(final String token, final String method, final Map<String, Object> params) {
        final String body = http().post()
                .uri(MCP_PATH)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body(Map.of("jsonrpc", "2.0", "id", requestId.incrementAndGet(), "method", method, "params", params))
                .retrieve()
                .body(String.class);

        return MAPPER.readTree(body);
    }

    private JsonNode call(final String user, final String tool, final Map<String, Object> arguments) {
        return rpc(mcpToken(user), "tools/call", Map.of("name", tool, "arguments", arguments));
    }

    private JsonNode ok(final String user, final String tool, final Map<String, Object> arguments) {
        final JsonNode response = call(user, tool, arguments);
        final JsonNode result = response.get("result");

        if (result == null || result.path("isError").asBoolean(false)) {
            throw new AssertionError("Tool error: " + response);
        }
        return MAPPER.readTree(result.get("content").get(0).get("text").asString());
    }

    private static List<String> ids(final JsonNode items) {
        final List<String> ids = new ArrayList<>();

        items.forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }

    @Test
    void theToolsListPublicAlbumsAndNothingElse() {
        final List<String> names = new ArrayList<>();

        for (final JsonNode tool : rpc(mcpToken(EDITOR), "tools/list", Map.of()).path("result").path("tools")) {
            names.add(tool.get("name").asString());
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean(false)).as(tool.get("name").asString()).isTrue();
            assertThat(tool.path("annotations").path("destructiveHint").asBoolean(true)).as(tool.get("name").asString()).isFalse();
        }

        assertThat(names).containsExactlyInAnyOrder("list_albums", "search_albums", "get_album", "list_spex");
    }

    @Test
    void anEditorGetsOnlyWhatIsPublic() throws Exception {
        final String publicAlbum = createAlbum("PUBLIC");
        final String membersAlbum = createAlbum("MEMBERS");
        final String unpublishedAlbum = createAlbum("UNPUBLISHED");
        final List<String> items = readyPhotos(publicAlbum, folder, "Kören på scenen", "Bakom kulisserna");

        graphQl(EDITOR)
                .document("mutation($ids: [ID!]!) { itemsUpdate(ids: $ids, input: {visibility: PUBLIC}) { items { visibility } } }")
                .variables(Map.of("ids", List.of(items.getFirst())))
                .execute()
                .errors().verify();

        final JsonNode listed = ok(EDITOR, "list_albums", args());

        assertThat(listed.get("totalCount").asInt()).isEqualTo(1);
        assertThat(ids(listed.get("items"))).containsExactly(publicAlbum);

        final JsonNode album = listed.get("items").get(0);

        assertThat(album.get("itemCount").asInt()).as("only the public item").isEqualTo(1);
        assertThat(album.get("year").asInt()).isEqualTo(2024);
        assertThat(album.get("spex").get(0).get("title").asString()).isEqualTo("Cleopatra");
        assertThat(album.get("url").asString()).isEqualTo("https://spexgram.test/albums/" + publicAlbum);
        assertThat(album.has("visibility")).isFalse();

        assertThat(ids(ok(ADMIN, "list_albums", args("spex", "cleopatra", "year", 2024)).get("items"))).containsExactly(publicAlbum);
        assertThat(ids(ok(EDITOR, "list_albums", args("spex", SPEX_ID)).get("items"))).containsExactly(publicAlbum);
        assertThat(ok(EDITOR, "list_albums", args("year", 1999)).get("items")).isEmpty();
        assertThat(ids(ok(EDITOR, "search_albums", args("query", "kårhus")).get("items"))).containsExactly(publicAlbum);

        assertThat(ok(EDITOR, "get_album", args("albumId", publicAlbum)).get("place").asString()).isEqualTo("Chalmers kårhus");
        for (final String hidden : List.of(membersAlbum, unpublishedAlbum)) {
            final JsonNode response = call(EDITOR, "get_album", args("albumId", hidden));

            assertThat(refused(response)).as(hidden).isTrue();
            assertThat(response.toString()).contains("NOT_FOUND");
        }

        final JsonNode spex = ok(VIEWER, "list_spex", args()).get("items");

        assertThat(spex).hasSize(1);
        assertThat(spex.get(0).get("albumCount").asInt()).isEqualTo(1);
        assertThat(spex.get(0).get("category").asString()).isEqualTo("Chalmersspexet");
    }

    @Test
    void onlyTokensOfTheMcpClientAreAccepted() {
        final String list = "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"tools/list\", \"params\": {}}";

        assertThat(status(token(EDITOR), MCP_PATH, list).value()).as("a web token on MCP").isEqualTo(401);
        assertThat(status(mcpToken(EDITOR), "/api/graphql", "{\"query\": \"{ years }\"}").value()).as("an MCP token on GraphQL").isEqualTo(401);
        assertThat(status(mcpToken(EDITOR), MCP_PATH, list).value()).isEqualTo(200);
    }
}
