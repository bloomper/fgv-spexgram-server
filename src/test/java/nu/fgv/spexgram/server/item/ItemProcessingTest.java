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

import nu.fgv.spexgram.server.immich.ImmichClient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static nu.fgv.spexgram.server.util.TestUuids.uuid;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an item copies of its asset in Immich.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class ItemProcessingTest {

    private static ImmichClient.Asset asset(final String thumbhash, final LocalDateTime takenAt) {
        return new ImmichClient.Asset(uuid(1), false, Instant.parse("2026-11-01T10:00:00Z"), thumbhash, takenAt,
                640, 480, null, 1234L, 57.689, 11.974, "IMG_1.jpg");
    }

    @Test
    void anItemTakesItsThumbhashAlwaysButItsDateOnlyWhileProcessing() {
        final Item item = new Item();
        final LocalDateTime taken = LocalDateTime.of(2024, 2, 14, 20, 0);

        ItemProcessing.copy(asset("1QcSHQRnh493V4dIh4eXh1h4kJUI", taken), item);
        assertThat(item.getThumbhash()).isEqualTo("1QcSHQRnh493V4dIh4eXh1h4kJUI");
        assertThat(item.getTakenAt()).isEqualTo(taken);
        assertThat(item.getWidth()).isEqualTo(640);

        item.setStatus(ItemStatus.READY);
        ItemProcessing.copy(asset("3OcRJYB4d3h/iIeHeEh3eIhw+j2w", taken.plusYears(1)), item);
        assertThat(item.getThumbhash()).isEqualTo("3OcRJYB4d3h/iIeHeEh3eIhw+j2w");
        assertThat(item.getTakenAt()).isEqualTo(taken);
    }
}
