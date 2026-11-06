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

import nu.fgv.spexgram.server.TestClock;
import nu.fgv.spexgram.server.config.SpexgramProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static nu.fgv.spexgram.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signed media addresses, as spexgram-media's nginx checks them.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class MediaSignerTest {

    private static SpexgramProperties properties(final String key) {
        return new SpexgramProperties(null, null, null, null,
                new SpexgramProperties.Media("https://media.example.org/", key, Duration.ofHours(4), null, null),
                null, null, null, null, null);
    }

    @Test
    void signsLikeNginxSecureLink() {
        // printf '%s' "1893456000<asset>/preview secret" | openssl md5 -binary | openssl base64 | tr +/ -_ | tr -d =
        assertThat(MediaSigner.signature(uuid(1), MediaSigner.Variant.PREVIEW, 1893456000L, "secret")).isEqualTo("YnzI4ByD5VZzStZPmQs0KQ");
    }

    @Test
    void anAddressNamesTheAssetVariantAndExpiry() {
        final MediaSigner signer = new MediaSigner(properties("secret"), new TestClock(Instant.parse("2030-01-01T00:00:00Z")));

        assertThat(signer.sign(uuid(1), MediaSigner.Variant.PREVIEW, Instant.ofEpochSecond(1893456000L)))
                .isEqualTo("https://media.example.org/a/00000000-0000-7000-8000-000000000001/preview/1893456000/YnzI4ByD5VZzStZPmQs0KQ");
    }

    @Test
    void addressesAreValidForAtLeastFourHoursRoundedUpToTheHour() {
        final MediaSigner signer = new MediaSigner(properties("secret"), new TestClock(Instant.parse("2026-11-05T10:20:00Z")));

        assertThat(signer.expiry()).isEqualTo(Instant.parse("2026-11-05T15:00:00Z"));
    }

    @Test
    void refusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new MediaSigner(properties(" "), new TestClock(Instant.EPOCH)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API_MEDIA_SIGNING_KEY");
    }
}
