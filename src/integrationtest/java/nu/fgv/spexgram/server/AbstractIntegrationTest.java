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


package nu.fgv.spexgram.server;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import nu.fgv.spexgram.server.immich.ImmichClient;
import nu.fgv.spexgram.server.item.ItemProcessing;
import nu.fgv.spexgram.server.report.VisitorReportLimiter;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JacksonJsonParser;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL, Keycloak (with the committed copy of {@code fgv-keycloak}'s realm plus fictitious users) and Immich (server,
 * PostgreSQL and Valkey, without machine learning) in Testcontainers; WireMock standing in for Spexregister's GraphQL
 * API and Cloudflare Turnstile; GreenMail as the mail server. The API sets Immich up at start, as in production. Every
 * test starts from empty tables, an empty Immich library and no mail.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integrationtest")
public abstract class AbstractIntegrationTest {

    protected static final String DOMAIN = "@spexgram.test";
    protected static final String VIEWER = "medlem" + DOMAIN;
    protected static final String EDITOR = "redaktor" + DOMAIN;
    protected static final String ADMIN = "admin" + DOMAIN;
    protected static final String NO_ACCESS = "utan" + DOMAIN;
    protected static final String PASSWORD = "s3cr3t-s3cr3t";
    protected static final String CLIENT_ID = "fgv-spexgram";
    protected static final String SPEX_ID = "00000000-0000-7000-8000-000000000001";
    protected static final String IMMICH_VERSION = "v3.3.6";
    protected static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());
    protected static final Path DATA = Path.of("build/integrationtest-data").toAbsolutePath();
    private static final String REALM = "fgv";
    private static final JacksonJsonParser JSON = new JacksonJsonParser();
    private static final Map<String, String> SECRETS = clientSecrets();
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
    private static final Random RANDOM = new Random();
    private static final int SMTP_PORT = freePort();
    protected static final GreenMail MAIL = new GreenMail(new ServerSetup(SMTP_PORT, null, ServerSetup.PROTOCOL_SMTP))
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());
    @ServiceConnection
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("mysqld", "--innodb-flush-log-at-trx-commit=0", "--innodb-doublewrite=0", "--skip-log-bin", "--performance-schema=OFF");
    private static final KeycloakContainer KEYCLOAK = new KeycloakContainer("quay.io/keycloak/keycloak:26.8").withRealmImportFile("/realm/fgv.json");
    private static final Network IMMICH_NETWORK = Network.newNetwork();
    private static final GenericContainer<?> IMMICH_POSTGRES = new GenericContainer<>(
            "ghcr.io/immich-app/postgres:14-vectorchord0.4.3-pgvectors0.2.0@sha256:bcf63357191b76a916ae5eb93464d65c07511da41e3bf7a8416db519b40b1c23")
            .withNetwork(IMMICH_NETWORK)
            .withNetworkAliases("immich-postgres")
            .withEnv(Map.of("POSTGRES_PASSWORD", "postgres", "POSTGRES_USER", "postgres", "POSTGRES_DB", "immich"))
            .withSharedMemorySize(128L * 1024 * 1024)
            .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"));
    private static final GenericContainer<?> IMMICH_VALKEY = new GenericContainer<>(
            "docker.io/valkey/valkey:9@sha256:c123e3715db63d06d4ad6964884037aa0d5d4d703939b9929954112889708e1d")
            .withNetwork(IMMICH_NETWORK)
            .withNetworkAliases("immich-valkey");
    private static final GenericContainer<?> IMMICH = new GenericContainer<>("ghcr.io/immich-app/immich-server:" + IMMICH_VERSION)
            .withNetwork(IMMICH_NETWORK)
            .withEnv(Map.of("DB_HOSTNAME", "immich-postgres", "DB_USERNAME", "postgres", "DB_PASSWORD", "postgres",
                    "DB_DATABASE_NAME", "immich", "REDIS_HOSTNAME", "immich-valkey", "IMMICH_MACHINE_LEARNING_ENABLED", "false"))
            .withExposedPorts(2283)
            .dependsOn(IMMICH_POSTGRES, IMMICH_VALKEY)
            .waitingFor(Wait.forHttp("/api/server/ping").forPort(2283).withStartupTimeout(Duration.ofMinutes(4)));

    static {
        Startables.deepStart(MYSQL, KEYCLOAK, IMMICH).join();
        WIREMOCK.start();
        MAIL.start();
        prepareKeycloak();
    }

    @LocalServerPort
    protected int localPort;
    @Autowired
    protected JdbcClient jdbcClient;
    @Autowired
    protected ImmichClient immichClient;
    @Autowired
    protected ItemProcessing processing;
    @Autowired
    private VisitorReportLimiter reportLimiter;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM);
        registry.add("spexgram.keycloak.url", KEYCLOAK::getAuthServerUrl);
        registry.add("spexgram.keycloak.service-client-secret", () -> SECRETS.get("fgv-spexgram-service"));
        registry.add("spexgram.keycloak.admin-client-secret", () -> SECRETS.get("fgv-admin"));
        registry.add("spexgram.immich.url", AbstractIntegrationTest::immichUrl);
        registry.add("spexgram.spexregister.graphql-url", () -> WIREMOCK.baseUrl() + "/api/graphql");
        registry.add("spexgram.turnstile.verify-url", () -> WIREMOCK.baseUrl() + "/turnstile/v0/siteverify");
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> SMTP_PORT);
    }

    /**
     * Immich's address on the host, for checking its API keys directly.
     */
    protected static String immichUrl() {
        return "http://%s:%d".formatted(IMMICH.getHost(), IMMICH.getMappedPort(2283));
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The client secrets in the committed realm copy, which are not real secrets.
     */
    private static Map<String, String> clientSecrets() {
        try (InputStream stream = AbstractIntegrationTest.class.getResourceAsStream("/realm/fgv.json")) {
            final Map<String, Object> realm = JSON.parseMap(new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8));
            final Map<String, String> secrets = new ConcurrentHashMap<>();

            for (final Object client : (List<?>) realm.get("clients")) {
                if (client instanceof Map<?, ?> map && map.get("secret") instanceof String secret) {
                    secrets.put(String.valueOf(map.get("clientId")), secret);
                }
            }
            return secrets;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void prepareKeycloak() {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);
            final ClientRepresentation web = realm.clients().findByClientId(CLIENT_ID).getFirst();

            web.setDirectAccessGrantsEnabled(true);
            realm.clients().get(web.getId()).update(web);

            createUser(realm, VIEWER, "Lisa", List.of("USER"));
            createUser(realm, EDITOR, "Erik", List.of("USER", "EDITOR"));
            createUser(realm, ADMIN, "Anna", List.of("USER", "ADMIN"));
            createUser(realm, NO_ACCESS, "Nils", List.of());
        }
    }

    private static String createUser(final RealmResource realm, final String email, final String firstName, final List<String> roles) {
        final CredentialRepresentation credential = new CredentialRepresentation();
        final UserRepresentation user = new UserRepresentation();

        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(PASSWORD);
        credential.setTemporary(false);
        user.setUsername(email);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName("Test");
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));

        try (Response response = realm.users().create(user)) {
            final String path = response.getLocation().getPath();
            final String userId = path.substring(path.lastIndexOf('/') + 1);

            grantClientRoles(realm, userId, CLIENT_ID, roles);
            return userId;
        }
    }

    private static void grantClientRoles(final RealmResource realm, final String userId, final String clientId, final List<String> roles) {
        if (roles.isEmpty()) {
            return;
        }

        final ClientResource client = realm.clients().get(realm.clients().findByClientId(clientId).getFirst().getId());
        final List<RoleRepresentation> representations = roles.stream().map(role -> client.roles().get(role).toRepresentation()).toList();

        realm.users().get(userId).roles().clientLevel(client.toRepresentation().getId()).add(representations);
    }

    /**
     * A user as Spexregister's sample data creates them: with roles on Spexregister's client only.
     */
    protected static void createSpexregisterUser(final String email) {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);

            grantClientRoles(realm, createUser(realm, email, "Spexregister", List.of()), "spexregister", List.of("USER"));
        }
    }

    /**
     * The client roles a user has on {@code fgv-spexgram}, or empty when the user does not exist.
     */
    protected static List<String> spexgramRoles(final String email) {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            final RealmResource realm = admin.realm(REALM);
            final List<UserRepresentation> users = realm.users().searchByEmail(email, true);

            if (users.isEmpty()) {
                return List.of();
            }

            final String clientUuid = realm.clients().findByClientId(CLIENT_ID).getFirst().getId();

            return realm.users().get(users.getFirst().getId()).roles().clientLevel(clientUuid).listAll().stream()
                    .map(RoleRepresentation::getName).sorted().toList();
        }
    }

    /**
     * A user's access token from {@code fgv-spexgram}, by password grant.
     */
    protected static String token(final String user) {
        return TOKENS.computeIfAbsent(user, _ -> "Bearer " + tokens(user).get("access_token"));
    }

    /**
     * A user's ID token from {@code fgv-spexgram}, which the API must refuse in place of an access token.
     */
    protected static String idToken(final String user) {
        return "Bearer " + tokens(user).get("id_token");
    }

    private static Map<String, Object> tokens(final String user) {
        final MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

        Map.of("grant_type", "password", "client_id", CLIENT_ID, "client_secret", SECRETS.get(CLIENT_ID),
                "username", user, "password", PASSWORD, "scope", "openid").forEach(form::add);

        final String response = RestClient.create()
                .post()
                .uri(KEYCLOAK.getAuthServerUrl() + "/realms/" + REALM + "/protocol/openid-connect/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);

        return JSON.parseMap(response);
    }

    /**
     * Asserts that the response has an error with the given {@code extensions.classification}, e.g. {@code FORBIDDEN}.
     */
    protected static void expectError(final GraphQlTester.Response response, final String code) {
        response.errors().satisfy(errors -> assertThat(errors)
                .as("errors")
                .anySatisfy(error -> assertThat(String.valueOf(error.getErrorType())).isEqualTo(code)));
    }

    /**
     * Spexregister answering with spex {@value #SPEX_ID}, Cleopatra 2024, and Turnstile accepting every token but
     * {@code bad}.
     */
    protected static void stubsUp() {
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(post(urlEqualTo("/api/graphql"))
                .withRequestBody(containing("SpexById"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"data": {"spex": {"id": "00000000-0000-7000-8000-000000000001", "year": "2024", "title": "Cleopatra", "revival": false, "parent": null,
                                           "category": {"id": "00000000-0000-7000-8000-000000000002", "name": "Chalmersspexet"}}}}""")));
        WIREMOCK.stubFor(post(urlEqualTo("/turnstile/v0/siteverify"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"success\": true, \"hostname\": \"spexgram.test\"}")));
        WIREMOCK.stubFor(post(urlEqualTo("/turnstile/v0/siteverify"))
                .withRequestBody(containing("response=elsewhere"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"success\": true, \"hostname\": \"evil.example\"}")));
        WIREMOCK.stubFor(post(urlEqualTo("/turnstile/v0/siteverify"))
                .withRequestBody(containing("response=bad"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"success\": false}")));
    }

    @BeforeEach
    void resetState() throws Exception {
        reportLimiter.clear();
        jdbcClient.sql("SET FOREIGN_KEY_CHECKS = 0").update();
        for (final String table : List.of("audit_entry", "report", "contribution_review", "album_suggestion", "album_spex", "item",
                "album", "upload_session", "app_user")) {
            jdbcClient.sql("TRUNCATE TABLE " + table).update();
        }
        jdbcClient.sql("SET FOREIGN_KEY_CHECKS = 1").update();
        // Released rather than deleted: ShedLock remembers which lock rows it has made and only updates them after that
        jdbcClient.sql("UPDATE shedlock SET lock_until = UTC_TIMESTAMP(3)").update();
        // Back to the defaults, which an admin test may have changed
        jdbcClient.sql("""
                UPDATE settings SET welcome_sv = NULL, welcome_en = NULL, public_welcome_sv = NULL, public_welcome_en = NULL,
                    contact_email = NULL, max_upload_gb = 4, max_video_minutes = 10, contribution_max_items = 500,
                    contribution_max_gb = 20, user_downloads_originals = 0, show_positions = 1""").update();

        final List<UUID> assets = new ArrayList<>();

        immichClient.forEachAsset(asset -> assets.add(asset.id()));
        immichClient.delete(assets);
        MAIL.purgeEmailFromAllMailboxes();
        stubsUp();
    }

    protected HttpGraphQlTester graphQl(final @Nullable String user) {
        return HttpGraphQlTester.create(WebTestClient.bindToServer()
                .baseUrl("http://localhost:%d/api/graphql".formatted(localPort))
                .responseTimeout(Duration.ofSeconds(120))
                .defaultHeaders(headers -> {
                    if (user != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, token(user));
                    }
                    headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en");
                })
                .build());
    }

    protected RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + localPort).build();
    }

    /**
     * A JPEG of random coloured squares, different on every call, so Immich never takes it for a duplicate.
     */
    protected static Path photo(final Path folder, final String name) throws IOException {
        final BufferedImage image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();

        for (int i = 0; i < 40; i++) {
            g.setColor(new Color(RANDOM.nextInt(0x1000000)));
            g.fillRect(RANDOM.nextInt(640), RANDOM.nextInt(480), 20 + RANDOM.nextInt(200), 20 + RANDOM.nextInt(200));
        }
        g.dispose();

        final Path file = folder.resolve(name);

        ImageIO.write(image, "jpg", file.toFile());
        return file;
    }

    /**
     * Uploads a file with tus, in one chunk, as the given user, and returns the upload's id.
     */
    protected String upload(final String user, final Path file) throws IOException {
        final byte[] content = Files.readAllBytes(file);
        final String name = Base64.getEncoder().encodeToString(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
        final String location = Objects.requireNonNull(http().post()
                .uri("/api/uploads")
                .header(HttpHeaders.AUTHORIZATION, token(user))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Length", String.valueOf(content.length))
                .header("Upload-Metadata", "filename " + name)
                .retrieve()
                .toBodilessEntity()
                .getHeaders()
                .getLocation()).toString();

        http().patch()
                .uri(location)
                .header(HttpHeaders.AUTHORIZATION, token(user))
                .header("Tus-Resumable", "1.0.0")
                .header("Upload-Offset", "0")
                .contentType(MediaType.parseMediaType("application/offset+octet-stream"))
                .body(content)
                .retrieve()
                .toBodilessEntity();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    protected String createAlbum(final String visibility) {
        final String id = graphQl(EDITOR)
                .document("mutation { albumCreate(input: {spexIds: [\"" + SPEX_ID + "\"], place: \"Chalmers kårhus\"}) { id visibility } }")
                .execute()
                .path("albumCreate.visibility").entity(String.class).isEqualTo("UNPUBLISHED")
                .path("albumCreate.id").entity(String.class).get();

        graphQl(EDITOR)
                .document("mutation($id: ID!, $v: Visibility!) { albumSetVisibility(id: $id, visibility: $v) { album { visibility } } }")
                .variables(Map.of("id", id, "v", visibility))
                .execute()
                .path("albumSetVisibility.album.visibility").entity(String.class).isEqualTo(visibility);
        return id;
    }

    /**
     * Photos uploaded by the editor to the album and processed by Immich, with the given captions; their ids.
     */
    protected List<String> readyPhotos(final String albumId, final Path folder, final String... captions) throws Exception {
        final List<String> uploads = new ArrayList<>();

        for (int i = 0; i < captions.length; i++) {
            uploads.add(upload(EDITOR, photo(folder, "foto-" + i + ".jpg")));
        }

        final List<String> ids = graphQl(EDITOR)
                .document("mutation($album: ID!, $uploads: [String!]!) { itemsAdd(albumId: $album, uploadIds: $uploads) { outcome item { id } } }")
                .variables(Map.of("album", albumId, "uploads", uploads))
                .execute()
                .path("itemsAdd[*].outcome").entityList(String.class).satisfies(outcomes -> assertThat(outcomes).containsOnly("CREATED"))
                .path("itemsAdd[*].item.id").entityList(String.class).get();

        for (int i = 0; i < ids.size(); i++) {
            graphQl(EDITOR)
                    .document("mutation($id: ID!, $caption: String) { itemUpdate(id: $id, input: {caption: $caption}) { id } }")
                    .variables(Map.of("id", ids.get(i), "caption", captions[i]))
                    .execute()
                    .errors().verify();
        }
        awaitReady(ids);
        return ids;
    }

    /**
     * Follows Immich's processing, as the scheduled job does, until the items are no longer processing.
     */
    protected void awaitReady(final List<String> ids) throws InterruptedException {
        final Instant deadline = Instant.now().plus(Duration.ofMinutes(2));

        while (Instant.now().isBefore(deadline)) {
            processing.poll();
            if (jdbcClient.sql("SELECT COUNT(*) FROM item WHERE status = 'PROCESSING'").query(Long.class).single() == 0) {
                break;
            }
            Thread.sleep(1_000);
        }
        for (final String id : ids) {
            final String asset = jdbcClient.sql("SELECT immich_asset_id FROM item WHERE uuid = :id").param("id", id).query(String.class).single();

            assertThat(jdbcClient.sql("SELECT status FROM item WHERE uuid = :id").param("id", id).query(String.class).single())
                    .as("status of %s, in Immich %s", id, immichClient.asset(UUID.fromString(asset))).isEqualTo("READY");
        }
    }
}
