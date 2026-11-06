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

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Limits visitors' reports to a few an hour per client address and per email address, so that the report form cannot
 * be used to flood editors with email. Kept in memory: a restart forgets it, which is fine for a limit this coarse.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class VisitorReportLimiter {

    static final int MAX_PER_HOUR = 5;
    private static final Duration WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Map<String, Deque<Instant>> attempts = new HashMap<>();

    public VisitorReportLimiter(final Clock clock) {
        this.clock = clock;
    }

    /**
     * Counts an attempt for the client and the email address; false when either has made too many in the last hour.
     */
    public synchronized boolean tryAcquire(final @Nullable String clientIp, final String email) {
        final Instant now = clock.instant();
        final String ipKey = "ip:" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp.strip());
        final String emailKey = "email:" + email.strip().toLowerCase(Locale.ROOT);

        attempts.values().forEach(times -> prune(times, now));
        attempts.values().removeIf(Deque::isEmpty);
        if (count(ipKey) >= MAX_PER_HOUR || count(emailKey) >= MAX_PER_HOUR) {
            return false;
        }
        attempts.computeIfAbsent(ipKey, _ -> new ArrayDeque<>()).addLast(now);
        attempts.computeIfAbsent(emailKey, _ -> new ArrayDeque<>()).addLast(now);
        return true;
    }

    /**
     * Forgets every attempt, e.g. between integration tests.
     */
    public synchronized void clear() {
        attempts.clear();
    }

    private int count(final String key) {
        final Deque<Instant> times = attempts.get(key);

        return times == null ? 0 : times.size();
    }

    private static void prune(final Deque<Instant> times, final Instant now) {
        while (!times.isEmpty() && !times.peekFirst().isAfter(now.minus(WINDOW))) {
            times.removeFirst();
        }
    }
}
