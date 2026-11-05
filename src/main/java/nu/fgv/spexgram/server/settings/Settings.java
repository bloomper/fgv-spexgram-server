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


package nu.fgv.spexgram.server.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * The one row of settings (5.8 of the spec).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "settings")
@Getter
@Setter
public class Settings {

    public static final long ID = 1L;

    @Id
    private Long id = ID;

    /**
     * The start page's text for members.
     */
    @Column(name = "welcome_sv")
    private @Nullable String welcomeSv;

    @Column(name = "welcome_en")
    private @Nullable String welcomeEn;

    /**
     * The start page's text for visitors.
     */
    @Column(name = "public_welcome_sv")
    private @Nullable String publicWelcomeSv;

    @Column(name = "public_welcome_en")
    private @Nullable String publicWelcomeEn;

    /**
     * The address shown to visitors, e.g. for questions about a photo.
     */
    @Column(name = "contact_email")
    private @Nullable String contactEmail;

    @Column(name = "max_upload_gb", nullable = false)
    private int maxUploadGb = 4;

    @Column(name = "max_video_minutes", nullable = false)
    private int maxVideoMinutes = 10;

    @Column(name = "contribution_max_items", nullable = false)
    private int contributionMaxItems = 500;

    @Column(name = "contribution_max_gb", nullable = false)
    private int contributionMaxGb = 20;

    /**
     * Whether {@code USER} may download originals and albums as zips; {@code EDITOR} and {@code ADMIN} always may.
     */
    @Column(name = "user_downloads_originals", nullable = false)
    private boolean userDownloadsOriginals;

    /**
     * Whether members see positions, on the map and with items; editors always do, visitors never.
     */
    @Column(name = "show_positions", nullable = false)
    private boolean showPositions = true;

    @Version
    private Long version;

    @Column(name = "last_modified_by")
    private @Nullable String lastModifiedBy;

    @Column(name = "last_modified_at")
    private @Nullable Instant lastModifiedAt;
}
