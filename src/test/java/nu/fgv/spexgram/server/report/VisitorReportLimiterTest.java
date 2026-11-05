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

package nu.fgv.spexgram.server.report;

import nu.fgv.spexgram.server.TestClock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Visitors' reports: a few an hour per address and per email address, and Turnstile tokens solved on this site only.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class VisitorReportLimiterTest {

    @Test
    void aVisitorMakesAFewReportsAnHourPerAddressAndPerEmail() {
        final TestClock clock = new TestClock(Instant.parse("2026-11-05T10:00:00Z"));
        final VisitorReportLimiter limiter = new VisitorReportLimiter(clock);

        for (int i = 0; i < VisitorReportLimiter.MAX_PER_HOUR; i++) {
            assertThat(limiter.tryAcquire("192.0.2.1", "a" + i + "@example.org")).isTrue();
        }
        assertThat(limiter.tryAcquire("192.0.2.1", "ny@example.org")).as("the address").isFalse();
        for (int i = 0; i < VisitorReportLimiter.MAX_PER_HOUR; i++) {
            assertThat(limiter.tryAcquire("198.51.100." + i, "B@example.org")).isTrue();
        }
        assertThat(limiter.tryAcquire("203.0.113.9", "b@example.org")).as("the email, in any case").isFalse();

        clock.advance(Duration.ofHours(1));
        assertThat(limiter.tryAcquire("192.0.2.1", "b@example.org")).as("an hour later").isTrue();
    }

    @Test
    void aTokenMustBeSolvedOnTheWebsHostUnlessCloudflaresTestSecretIsUsed() {
        final JsonMapper json = JsonMapper.builder().build();

        assertThat(TurnstileVerifier.accepts(json.readTree("{\"success\": true, \"hostname\": \"spexgram.fgv.nu\"}"), "spexgram.fgv.nu")).isTrue();
        assertThat(TurnstileVerifier.accepts(json.readTree("{\"success\": true, \"hostname\": \"evil.example\"}"), "spexgram.fgv.nu")).isFalse();
        assertThat(TurnstileVerifier.accepts(json.readTree("{\"success\": false, \"hostname\": \"spexgram.fgv.nu\"}"), "spexgram.fgv.nu")).isFalse();
        assertThat(TurnstileVerifier.accepts(json.readTree("{\"success\": true, \"hostname\": \"example.com\"}"), null)).isTrue();
        assertThat(TurnstileVerifier.isTestSecret("1x0000000000000000000000000000000AA")).isTrue();
        assertThat(TurnstileVerifier.isTestSecret("0x4AAAAAAAreal")).isFalse();
        assertThat(TurnstileVerifier.hostOf("https://spexgram.fgv.nu")).isEqualTo("spexgram.fgv.nu");
    }
}
