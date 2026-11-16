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

package nu.fgv.spexgram.server.spex;

import nu.fgv.spexgram.server.user.CallerService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spexipedia tells Spexgram that its change feed has something new; the call only starts a pull and carries no data.
 * Requires {@code NOTIFY} on {@code fgv-spexgram}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class SyncApi {

    private final SpexipediaSync sync;
    private final CallerService callerService;

    public SyncApi(final SpexipediaSync sync, final CallerService callerService) {
        this.sync = sync;
        this.callerService = callerService;
    }

    @PostMapping("/api/sync/notify")
    public ResponseEntity<Void> notifyChanged() {
        if (!callerService.current().isNotifier()) {
            throw new AccessDeniedException("NOTIFY required");
        }
        sync.pullSoon();
        return ResponseEntity.accepted().build();
    }
}
