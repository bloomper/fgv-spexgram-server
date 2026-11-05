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


package nu.fgv.spexgram.server.media;

import nu.fgv.spexgram.server.config.SpexgramProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Signs media addresses the way nginx's {@code secure_link} in {@code spexgram-media} checks them, so that the media
 * server passes requests to Immich without asking the API:
 *
 * <pre>
 * {base}/a/{assetId}/{variant}/{expires}/{signature}
 * signature = base64url(md5("{expires}{assetId}/{variant} {key}")), without padding
 * </pre>
 *
 * <p>An address is valid for at least {@code spexgram.media.link-validity}. The expiry is rounded up to the next whole
 * hour, so that a page loaded twice within the hour gets the same addresses and the browser's cache works.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class MediaSigner {

    private static final long ROUNDING_SECONDS = Duration.ofHours(1).toSeconds();

    private final String baseUrl;
    private final String key;
    private final Duration validity;
    private final Clock clock;

    public MediaSigner(final SpexgramProperties properties, final Clock clock) {
        final String configured = properties.media().signingKey();

        if (configured == null || configured.isBlank()) {
            // Never fall back to a known key: anyone could sign addresses to every original with it
            throw new IllegalStateException("API_MEDIA_SIGNING_KEY is not set");
        }
        this.baseUrl = properties.media().baseUrl().replaceAll("/+$", "");
        this.key = configured.strip();
        this.validity = properties.media().linkValidity();
        this.clock = clock;
    }

    static String signature(final UUID assetId, final Variant variant, final long expires, final String key) {
        try {
            final byte[] digest = MessageDigest.getInstance("MD5")
                    .digest((expires + assetId.toString() + "/" + variant.path() + " " + key).getBytes(StandardCharsets.UTF_8));

            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * When addresses signed now expire.
     */
    public Instant expiry() {
        final long seconds = clock.instant().plus(validity).getEpochSecond();

        return Instant.ofEpochSecond((seconds + ROUNDING_SECONDS - 1) / ROUNDING_SECONDS * ROUNDING_SECONDS);
    }

    public String sign(final UUID assetId, final Variant variant, final Instant expires) {
        final long epoch = expires.getEpochSecond();

        return "%s/a/%s/%s/%d/%s".formatted(baseUrl, assetId, variant.path(), epoch, signature(assetId, variant, epoch, key));
    }

    /**
     * What the media server serves of an asset, each from Immich's matching endpoint.
     */
    public enum Variant {
        /**
         * About 250 pixels, WebP.
         */
        THUMBNAIL,
        /**
         * About 1440 pixels on the long side, JPEG without metadata.
         */
        PREVIEW,
        /**
         * The uploaded file.
         */
        ORIGINAL,
        /**
         * A video for playing in the browser: Immich's transcoded version, or the original when it needed none.
         */
        VIDEO;

        public String path() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
