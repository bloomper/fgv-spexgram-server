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

import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.album.AlbumKind;
import nu.fgv.spexgram.server.album.AlbumKindRepository;
import nu.fgv.spexgram.server.album.AlbumRepository;
import nu.fgv.spexgram.server.album.SpexRef;
import nu.fgv.spexgram.server.audit.AuditEntry;
import nu.fgv.spexgram.server.audit.AuditEntryRepository;
import nu.fgv.spexgram.server.config.KeycloakConfig;
import nu.fgv.spexgram.server.config.SpexgramProperties;
import nu.fgv.spexgram.server.contribution.AlbumSuggestion;
import nu.fgv.spexgram.server.contribution.AlbumSuggestionRepository;
import nu.fgv.spexgram.server.contribution.ContributionReview;
import nu.fgv.spexgram.server.contribution.ContributionReviewRepository;
import nu.fgv.spexgram.server.immich.ImmichBootstrap;
import nu.fgv.spexgram.server.immich.ImmichClient;
import nu.fgv.spexgram.server.immich.ImmichRejectedException;
import nu.fgv.spexgram.server.item.ContributionStatus;
import nu.fgv.spexgram.server.item.Item;
import nu.fgv.spexgram.server.item.ItemProcessing;
import nu.fgv.spexgram.server.item.ItemRepository;
import nu.fgv.spexgram.server.item.ItemStatus;
import nu.fgv.spexgram.server.report.Report;
import nu.fgv.spexgram.server.report.ReportRepository;
import nu.fgv.spexgram.server.settings.SettingsService;
import nu.fgv.spexgram.server.spex.SpexDto;
import nu.fgv.spexgram.server.spex.SpexService;
import nu.fgv.spexgram.server.spex.SpexregisterClient;
import nu.fgv.spexgram.server.upload.UploadService;
import nu.fgv.spexgram.server.user.AppUser;
import nu.fgv.spexgram.server.user.AppUserRepository;
import nu.fgv.spexgram.server.util.security.SecurityUtil;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.jspecify.annotations.Nullable;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * Replaces everything with sample data at every start while {@code spexgram.data.import-sample-data} is set (5.9 of the
 * spec): the database, the uploads folder and every asset in Immich (Immich's system user and API keys are kept).
 * Built on Spexregister's sample spex and users, so Spexregister must run with its sample data too; refuses to run in
 * the {@code production} profile.
 *
 * <p>The files are generated ({@link SampleMedia}) and go through Immich's real upload and processing. The archive has
 * albums of every kind and visibility, with and without spex, a public item in an album for members, unpublished and
 * trashed albums and items, contributions waiting for review, an album suggestion, an open, a handled and a visitor's
 * report, unsorted items, a video Immich must transcode, one too long and a file that is not an image.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class SampleDataImporter {

    // Spexregister's sample users, which its sample data import creates; they get roles here
    static final String ADMIN = "admin@fgv.nu";
    static final String EDITOR = "editor@fgv.nu";
    static final String USER = "user@fgv.nu";
    // Shared with the other applications' sample data: an account without access
    static final String NO_ACCESS = "no-access@fgv.nu";
    static final String VISITOR_EMAIL = "besokare@example.org";
    static final double[] CHALMERS = {57.689, 11.974};
    private static final int SPEXREGISTER_ATTEMPTS = 30;
    private static final Duration SPEXREGISTER_RETRY_INTERVAL = Duration.ofSeconds(10);
    private static final Duration PROCESSING_WAIT = Duration.ofMinutes(5);
    private static final List<String> TABLES = List.of("audit_entry", "report", "contribution_review", "album_suggestion",
            "album_spex", "item", "album", "album_kind", "upload_session", "app_user");
    private static final String[][] KINDS = {{"Föreställning", "Show"}, {"Repetition", "Rehearsal"}, {"Fest", "Party"},
            {"Turné", "Tour"}, {"Jubileum", "Jubilee"}, {"Övrigt", "Other"}};
    private static final List<String> CAPTIONS = List.of("Kören sjunger öppningsnumret", "Hjältinnan på scenen",
            "Bakom scenen före premiären", "Orkestern stämmer", "Sminkningen är i full gång", "Applåder efter finalen",
            "Fest i kårhusets matsal", "Kulissbygge på lördagen", "Ensemblen på trappan", "Tackkören");
    private static final List<String> PLACES = List.of("Chalmers kårhus", "RunAn", "Konserthuset", "Lindholmen", "Pustervik");
    private static final List<String> PHOTOGRAPHERS = List.of("Foto: Anna Ängström", "Foto: Björn Öberg", "Foto: Åsa Lind");

    private final SpexgramProperties properties;
    private final Environment environment;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final SpexregisterClient spexregisterClient;
    private final SpexService spexService;
    private final Keycloak keycloak;
    private final ImmichBootstrap immichBootstrap;
    private final ImmichClient immichClient;
    private final ItemProcessing processing;
    private final UploadService uploadService;
    private final SettingsService settingsService;
    private final AlbumRepository albumRepository;
    private final AlbumKindRepository kindRepository;
    private final ItemRepository itemRepository;
    private final AppUserRepository userRepository;
    private final ReportRepository reportRepository;
    private final ContributionReviewRepository reviewRepository;
    private final AlbumSuggestionRepository suggestionRepository;
    private final AuditEntryRepository auditRepository;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public SampleDataImporter(final SpexgramProperties properties, final Environment environment, final JdbcTemplate jdbcTemplate,
                              final TransactionTemplate transactionTemplate, final SpexregisterClient spexregisterClient,
                              final SpexService spexService, @Qualifier(KeycloakConfig.ADMIN) final Keycloak keycloak,
                              final ImmichBootstrap immichBootstrap, final ImmichClient immichClient,
                              final ItemProcessing processing, final UploadService uploadService,
                              final SettingsService settingsService, final AlbumRepository albumRepository,
                              final AlbumKindRepository kindRepository, final ItemRepository itemRepository,
                              final AppUserRepository userRepository, final ReportRepository reportRepository,
                              final ContributionReviewRepository reviewRepository,
                              final AlbumSuggestionRepository suggestionRepository,
                              final AuditEntryRepository auditRepository, final Clock clock) {
        this.properties = properties;
        this.environment = environment;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.spexregisterClient = spexregisterClient;
        this.spexService = spexService;
        this.keycloak = keycloak;
        this.immichBootstrap = immichBootstrap;
        this.immichClient = immichClient;
        this.processing = processing;
        this.uploadService = uploadService;
        this.settingsService = settingsService;
        this.albumRepository = albumRepository;
        this.kindRepository = kindRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.reportRepository = reportRepository;
        this.reviewRepository = reviewRepository;
        this.suggestionRepository = suggestionRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    private static UserRepresentation spexregisterUser(final RealmResource realm, final String email) {
        return realm.users().searchByEmail(email, true).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Spexregister's sample user " + email
                        + " is missing; start Spexregister with its sample data first"));
    }

    private static UUID uuid(final String kind, final int n) {
        return UUID.nameUUIDFromBytes(("spexgram-sample-%s-%d".formatted(kind, n)).getBytes(StandardCharsets.UTF_8));
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady() throws IOException {
        if (!properties.data().importSampleData()) {
            return;
        }
        if (Arrays.asList(environment.getActiveProfiles()).contains("production")) {
            throw new IllegalStateException("Refusing to import sample data in the production profile");
        }
        importSampleData();
    }

    public void importSampleData() throws IOException {
        final long started = System.nanoTime();
        final boolean large = "large".equalsIgnoreCase(properties.data().sampleDataSize());

        log.info("Importing {} sample data", large ? "large" : "small");
        // Truncating resets the ids, and the uuids are fixed, so sample links stay the same on every run
        jdbcTemplate.execute((java.sql.Connection connection) -> {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (final String table : TABLES) {
                    statement.execute("TRUNCATE TABLE " + table);
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        uploadService.wipe();
        immichBootstrap.awaitAndSetUp(Duration.ofMinutes(5));
        deleteAllAssets();

        final List<SpexDto> spex = spexWithRetry();
        final RealmResource realm = keycloak.realm(properties.keycloak().realm());
        final Map<String, AppUser> users = NotificationSuspender.whileSuspended(realm, () -> createUsers(realm));
        final List<AlbumKind> kinds = Objects.requireNonNull(transactionTemplate.execute(_ -> createKinds()));
        final Path folder = Files.createTempDirectory("spexgram-sample-");

        try {
            final Generator generator = new Generator(folder, large, users);
            final int albumCount = large ? 300 : 15;
            final List<Album> albums = new ArrayList<>();

            for (int i = 0; i < albumCount; i++) {
                albums.add(generator.album(i, spex, kinds));
            }
            generator.videos(albums.get(2));
            generator.unsorted();
            generator.contributions(albums.get(1), albums.get(3));
            generator.suggestion(kinds.get(2));
            transactionTemplate.executeWithoutResult(_ -> generator.trashAndReports(albums));
            transactionTemplate.executeWithoutResult(_ -> createAudit(users.get(EDITOR), albums));
            if (!large) {
                awaitProcessing();
            }
        } finally {
            FileSystemUtils.deleteRecursively(folder);
        }
        log.info("Imported sample data in {} s", Duration.ofNanos(System.nanoTime() - started).toSeconds());
    }

    private void deleteAllAssets() {
        final List<UUID> assets = new ArrayList<>();

        immichClient.forEachAsset(asset -> assets.add(asset.id()));
        for (int i = 0; i < assets.size(); i += 500) {
            immichClient.delete(assets.subList(i, Math.min(assets.size(), i + 500)));
        }
        if (!assets.isEmpty()) {
            log.info("Deleted {} assets in Immich", assets.size());
        }
    }

    /**
     * Follows Immich's processing until every sample item is ready, failed or rejected, for a while.
     */
    private void awaitProcessing() {
        final Instant deadline = Instant.now().plus(PROCESSING_WAIT);

        while (!itemRepository.findByStatus(ItemStatus.PROCESSING).isEmpty() && Instant.now().isBefore(deadline)) {
            processing.poll();
            try {
                Thread.sleep(2_000);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.info("{} sample items are still processing in Immich", itemRepository.findByStatus(ItemStatus.PROCESSING).size());
    }

    private List<SpexDto> spexWithRetry() {
        for (int attempt = 1; ; attempt++) {
            try {
                final List<SpexDto> spex = spexregisterClient.allSpex();

                if (spex.isEmpty()) {
                    throw new IllegalStateException("Spexregister has no spex; import its sample data first");
                }
                return spex.stream().sorted(Comparator.comparing((SpexDto s) -> s.year() == null ? 0 : s.year())).toList();
            } catch (final SpexregisterClient.SpexregisterCallException e) {
                if (attempt == SPEXREGISTER_ATTEMPTS) {
                    throw new IllegalStateException("Spexregister is unavailable, so sample data cannot be imported", e);
                }
                log.info("Waiting for Spexregister ({}/{})", attempt, SPEXREGISTER_ATTEMPTS);
                try {
                    Thread.sleep(SPEXREGISTER_RETRY_INTERVAL);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for Spexregister", interrupted);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- users and kinds

    /**
     * Gives Spexregister's sample users {@code admin@fgv.nu}, {@code editor@fgv.nu} and {@code user@fgv.nu} the roles
     * of the same name on this client, and creates {@code no-access@fgv.nu}, without roles, when it is missing. Refuses
     * without Spexregister's sample users, which keeps the import away from real environments.
     */
    private Map<String, AppUser> createUsers(final RealmResource realm) {
        final ClientResource client = realm.clients().get(realm.clients().findByClientId(SecurityUtil.CLIENT_ID).getFirst().getId());
        final String clientUuid = client.toRepresentation().getId();
        final Map<String, AppUser> users = new LinkedHashMap<>();

        users.put(ADMIN, grant(realm, client, clientUuid, spexregisterUser(realm, ADMIN), "USER", "EDITOR", "ADMIN"));
        users.put(EDITOR, grant(realm, client, clientUuid, spexregisterUser(realm, EDITOR), "USER", "EDITOR"));
        users.put(USER, grant(realm, client, clientUuid, spexregisterUser(realm, USER), "USER"));
        users.put(NO_ACCESS, recordUser(noAccessUser(realm)));
        return users;
    }

    private AppUser grant(final RealmResource realm, final ClientResource client, final String clientUuid,
                          final UserRepresentation user, final String... roles) {
        final List<RoleRepresentation> representations = Arrays.stream(roles)
                .map(role -> client.roles().get(role).toRepresentation())
                .toList();

        realm.users().get(user.getId()).roles().clientLevel(clientUuid).add(representations);
        return recordUser(user);
    }

    private AppUser recordUser(final UserRepresentation representation) {
        final Instant now = clock.instant();
        final AppUser user = new AppUser();

        user.setSubject(representation.getId());
        user.setUsername(representation.getUsername());
        user.setDisplayName(representation.getFirstName());
        user.setEmail(representation.getEmail());
        user.setCreatedAt(now);
        user.setLastSeenAt(now);
        return Objects.requireNonNull(transactionTemplate.execute(_ -> userRepository.save(user)));
    }

    private UserRepresentation noAccessUser(final RealmResource realm) {
        final List<UserRepresentation> existing = realm.users().searchByEmail(NO_ACCESS, true);

        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        final CredentialRepresentation credential = new CredentialRepresentation();
        final UserRepresentation user = new UserRepresentation();

        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(properties.data().sampleUserPassword());
        credential.setTemporary(false);
        user.setUsername(NO_ACCESS);
        user.setEmail(NO_ACCESS);
        user.setFirstName("No");
        user.setLastName("Access");
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));

        try (Response response = realm.users().create(user)) {
            if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
                throw new IllegalStateException("Could not create user " + NO_ACCESS + " in Keycloak: " + response.getStatus());
            }

            final String path = response.getLocation().getPath();

            return realm.users().get(path.substring(path.lastIndexOf('/') + 1)).toRepresentation();
        }
    }

    /**
     * The kinds a new database starts with, as in the migration.
     */
    private List<AlbumKind> createKinds() {
        final List<AlbumKind> kinds = new ArrayList<>();

        for (int i = 0; i < KINDS.length; i++) {
            final AlbumKind kind = new AlbumKind();

            kind.setUuid(UUID.fromString("00000000-0000-7000-8000-00000000c00" + (i + 1)));
            kind.setNameSv(KINDS[i][0]);
            kind.setNameEn(KINDS[i][1]);
            kind.setPosition(i + 1);
            kind.setCreatedBy("system");
            kinds.add(kindRepository.save(kind));
        }
        return kinds;
    }

    private void createAudit(final AppUser editor, final List<Album> albums) {
        int minutes = 0;

        for (final Album album : albums.stream().limit(8).toList()) {
            final AuditEntry entry = new AuditEntry();

            entry.setAt(clock.instant().minus(Duration.ofMinutes(minutes += 41)));
            entry.setActorSubject(editor.getSubject());
            entry.setActorName(editor.getDisplayName());
            entry.setAction("CREATE");
            entry.setEntityType("ALBUM");
            entry.setEntityId(album.getUuid().toString());
            entry.setSummary("Skapade albumet " + album.getTitle());
            auditRepository.save(entry);
        }
    }

    /**
     * Makes the files, uploads them to Immich and records the items, numbering them so that every run gives the same
     * ids.
     */
    private final class Generator {

        private final Path folder;
        private final boolean large;
        private final Map<String, AppUser> users;
        private final Random random = new Random(42);
        private int number;

        Generator(final Path folder, final boolean large, final Map<String, AppUser> users) {
            this.folder = folder;
            this.large = large;
            this.users = users;
        }

        private int itemsPerAlbum() {
            return large ? 66 : 20;
        }

        Album album(final int index, final List<SpexDto> spex, final List<AlbumKind> kinds) throws IOException {
            final AlbumKind kind = kinds.get(index % kinds.size());
            final SpexDto picked = index % 5 == 4 ? null : spex.get(index % spex.size());
            final int year = picked != null && picked.year() != null ? picked.year() : 1990 + index;
            final Visibility visibility = index == 0 || index % 4 == 3 ? Visibility.PUBLIC
                    : index % 6 == 5 ? Visibility.UNPUBLISHED : Visibility.MEMBERS;
            final Album album = Objects.requireNonNull(transactionTemplate.execute(_ -> {
                final Album created = new Album();

                created.setUuid(uuid("album", index));
                created.setKind(kind);
                if (picked != null) {
                    final SpexRef ref = new SpexRef();

                    spexService.copy(picked, ref);
                    created.getSpex().add(ref);
                    created.setTitle("%s %d – %s".formatted(picked.title(), year, kind.getNameSv().toLowerCase(java.util.Locale.ROOT)));
                } else {
                    created.setTitle("%s i kårhuset %d".formatted(kind.getNameSv(), year));
                }
                created.setDateFrom(LocalDate.of(year, 2, 14));
                created.setDateTo(kind.getNameEn().equals("Tour") ? LocalDate.of(year, 2, 20) : null);
                created.setPlace(PLACES.get(index % PLACES.size()));
                created.setDescription("Exempeldata: genererade bilder med albumets titel, inga riktiga foton. Åäö fungerar.");
                created.setVisibility(visibility);
                created.setCreatedBy("system");
                return albumRepository.save(created);
            }));

            for (int j = 0; j < itemsPerAlbum(); j++) {
                final int position = j;
                final Visibility itemVisibility = position % 13 == 12 ? Visibility.UNPUBLISHED
                        : visibility == Visibility.PUBLIC && position % 5 != 4 ? Visibility.PUBLIC
                        // The public item in an album for members, e.g. a group photo shown in Spexipedia
                        : index == 1 && position == 0 ? Visibility.PUBLIC : Visibility.MEMBERS;
                final LocalDateTime taken = LocalDateTime.of(year, 2, 14, 19, 30).plusMinutes(position * 3L);

                photo(album, album.getTitle(), index / 15f % 1, taken, itemVisibility, users.get(EDITOR), null);
            }
            return album;
        }

        /**
         * One generated photo, uploaded; with a position near Chalmers for every third, turned by its metadata for
         * every seventh and as PNG for every tenth.
         */
        @Nullable Item photo(final @Nullable Album album, final String title, final float hue, final LocalDateTime taken,
                             final Visibility visibility, final AppUser uploader, final @Nullable ContributionStatus contribution) throws IOException {
            final int n = ++number;
            final boolean png = n % 10 == 0;
            final boolean rotated = !png && n % 7 == 0;
            final double[] position = !png && n % 3 == 0
                    ? new double[]{CHALMERS[0] + random.nextDouble() * 0.004, CHALMERS[1] + random.nextDouble() * 0.006} : null;
            final Path file = folder.resolve("spexgram-%05d.%s".formatted(n, png ? "png" : "jpg"));
            final var image = SampleMedia.image(large ? 400 : 1200, large ? 300 : 900, hue, n, title, rotated);

            if (png) {
                SampleMedia.png(image, file);
            } else {
                SampleMedia.jpeg(image, file, taken, position, rotated);
            }
            try {
                return item(album, file, taken, visibility, uploader, contribution, CAPTIONS.get(n % CAPTIONS.size()));
            } finally {
                Files.delete(file);
            }
        }

        private @Nullable Item item(final @Nullable Album album, final Path file, final LocalDateTime taken,
                                    final Visibility visibility, final AppUser uploader,
                                    final @Nullable ContributionStatus contribution, final String caption) throws IOException {
            final ImmichClient.Upload upload;
            final Instant created = taken.toInstant(ZoneOffset.UTC);

            try {
                upload = immichClient.upload(file, file.getFileName().toString(), created, created);
            } catch (final ImmichRejectedException e) {
                log.info("Immich rejected the sample file {}, as it should: {}", file.getFileName(), e.getMessage());
                return null;
            }

            final long size = Files.size(file);
            final int n = number;

            return transactionTemplate.execute(_ -> {
                final Item item = new Item();

                item.setUuid(uuid("item", n));
                item.setAlbum(album);
                item.setImmichAssetId(upload.id());
                item.setOriginalFileName(file.getFileName().toString());
                item.setFileSize(size);
                item.setTakenAt(taken);
                item.setCaption(album == null ? null : caption);
                item.setPhotographer(album == null ? null : n % 4 == 0 ? null : PHOTOGRAPHERS.get(n % PHOTOGRAPHERS.size()));
                item.setVisibility(visibility);
                item.setContributionStatus(contribution);
                item.setUploadedBySubject(uploader.getSubject());
                item.setUploadedByName(uploader.getDisplayName());
                item.setStatus(ItemStatus.PROCESSING);
                item.setProcessingSince(clock.instant());
                item.setCreatedBy(uploader.getSubject());
                return itemRepository.save(item);
            });
        }

        /**
         * Videos in one album: two that play as they are, one Immich must transcode, one too long, which is rejected
         * once Immich knows its length, and a text file, which Immich rejects at once.
         */
        void videos(final Album album) throws IOException {
            final int longMinutes = settingsService.current().getMaxVideoMinutes();

            for (final SampleMedia.Video video : SampleMedia.videos(folder, longMinutes)) {
                number++;
                item(album, video.file(), LocalDateTime.of(2019, 2, 14, 21, number % 60), Visibility.MEMBERS,
                        users.get(EDITOR), null, video.caption());
            }
        }

        /**
         * Items in Immich without an album, from two evenings, as after a bulk import.
         */
        void unsorted() throws IOException {
            for (int i = 0; i < 6; i++) {
                photo(null, "Osorterat", 0.6f, LocalDateTime.of(2019, 11, 8 + i / 3, 22, i * 5), Visibility.UNPUBLISHED,
                        users.get(ADMIN), null);
            }
        }

        /**
         * Three contributions waiting for review in an album for members, and one accepted in another.
         */
        void contributions(final Album waiting, final Album accepted) throws IOException {
            final AppUser user = users.get(USER);

            for (int i = 0; i < 3; i++) {
                photo(waiting, "Bidrag från en medlem", 0.1f, LocalDateTime.of(2019, 2, 14, 23, i * 10), Visibility.UNPUBLISHED,
                        user, ContributionStatus.WAITING);
            }

            final Item item = Objects.requireNonNull(photo(accepted, "Godkänt bidrag", 0.15f, LocalDateTime.of(2019, 2, 15, 0, 30),
                    Visibility.MEMBERS, user, ContributionStatus.ACCEPTED));

            transactionTemplate.executeWithoutResult(_ -> {
                final ContributionReview review = new ContributionReview();

                review.setItemId(item.getId());
                review.setAlbumTitle(accepted.getTitle());
                review.setFileName(item.getOriginalFileName());
                review.setContributorSubject(user.getSubject());
                review.setOutcome(ContributionReview.Outcome.ACCEPTED);
                review.setReviewerSubject(users.get(EDITOR).getSubject());
                review.setReviewedAt(clock.instant().minus(Duration.ofDays(2)));
                review.setNotifiedAt(clock.instant().minus(Duration.ofDays(1)));
                reviewRepository.save(review);
            });
        }

        /**
         * A member's album suggestion, with a contribution of theirs in it.
         */
        void suggestion(final AlbumKind kind) throws IOException {
            final AppUser user = users.get(USER);
            final Album album = Objects.requireNonNull(transactionTemplate.execute(_ -> {
                final Album suggested = new Album();
                final AlbumSuggestion suggestion = new AlbumSuggestion();

                suggested.setUuid(uuid("album", 1000));
                suggested.setKind(kind);
                suggested.setTitle("Sittning efter premiären");
                suggested.setDateFrom(LocalDate.of(2019, 2, 15));
                suggested.setDescription("Föreslaget av en medlem.");
                suggested.setSuggestedBy(user.getSubject());
                suggested.setCreatedBy(user.getSubject());
                albumRepository.save(suggested);
                suggestion.setUuid(uuid("suggestion", 1));
                suggestion.setAlbum(suggested);
                suggestion.setTitle(suggested.getTitle());
                suggestion.setSuggestedBySubject(user.getSubject());
                suggestion.setSuggestedByName(user.getDisplayName());
                suggestion.setCreatedAt(clock.instant().minus(Duration.ofHours(5)));
                suggestionRepository.save(suggestion);
                return suggested;
            }));

            photo(album, album.getTitle(), 0.8f, LocalDateTime.of(2019, 2, 15, 22, 0), Visibility.UNPUBLISHED, user, ContributionStatus.WAITING);
        }

        /**
         * An item and the last album in the trash, a member's open report that hid an item, a handled report and a
         * visitor's open report that took a public item off the public side.
         */
        void trashAndReports(final List<Album> albums) {
            final AppUser user = users.get(USER);
            final List<Item> inPublic = itemRepository.findByAlbum(albumRepository.findById(albums.get(0).getId()).orElseThrow());
            final List<Item> inMembers = itemRepository.findByAlbum(albumRepository.findById(albums.get(1).getId()).orElseThrow());
            final List<Item> inThird = itemRepository.findByAlbum(albumRepository.findById(albums.get(2).getId()).orElseThrow());

            inThird.get(5).setTrashedAt(clock.instant().minus(Duration.ofDays(1)));
            albumRepository.findById(albums.getLast().getId()).orElseThrow().setTrashedAt(clock.instant().minus(Duration.ofDays(3)));
            report(inMembers.get(3), Report.Reason.I_AM_IN_IT, user, null, Visibility.UNPUBLISHED, null);
            report(inThird.get(1), Report.Reason.WRONG_CAPTION_OR_ALBUM, user, null, null, Report.Outcome.CORRECTED);
            report(inPublic.get(1), Report.Reason.I_AM_IN_IT, null, VISITOR_EMAIL, Visibility.MEMBERS, null);
        }

        private void report(final Item item, final Report.Reason reason, final @Nullable AppUser reporter,
                            final @Nullable String visitorEmail, final @Nullable Visibility hideAs,
                            final Report.@Nullable Outcome outcome) {
            final Report report = new Report();

            report.setUuid(uuid("report", (int) reportRepository.count() + 1));
            report.setItem(item);
            report.setItemCaption(item.getCaption());
            report.setReporterSubject(reporter == null ? null : reporter.getSubject());
            report.setReporterName(reporter == null ? null : reporter.getDisplayName());
            report.setVisitorEmail(visitorEmail);
            report.setLocale("sv");
            report.setReason(reason);
            report.setMessage(visitorEmail != null ? "Jag syns på bilden och vill inte att den är publik." : "Exempeldata.");
            report.setCreatedAt(clock.instant().minus(Duration.ofHours(3)));
            if (hideAs != null) {
                report.setHiddenFrom(item.getVisibility());
                item.setVisibility(hideAs);
            }
            if (outcome != null) {
                report.setStatus(Report.Status.HANDLED);
                report.setOutcome(outcome);
                report.setOutcomeMessage("Bildtexten är rättad.");
                report.setHandledBy(users.get(EDITOR).getSubject());
                report.setHandledAt(clock.instant().minus(Duration.ofHours(1)));
            }
            reportRepository.save(report);
        }
    }
}
