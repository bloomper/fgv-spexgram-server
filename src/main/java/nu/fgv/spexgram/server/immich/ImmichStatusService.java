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


package nu.fgv.spexgram.server.immich;

import nu.fgv.spexgram.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Immich's status for admins (5.8 of the spec): its version, job queues and disk.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ImmichStatusService {

    private final ImmichClient client;
    private final ImmichKeys keys;
    private final CallerService callerService;

    public ImmichStatusService(final ImmichClient client, final ImmichKeys keys, final CallerService callerService) {
        this.client = client;
        this.keys = keys;
        this.callerService = callerService;
    }

    public ImmichStatusDto status() {
        callerService.current().requireAdmin();
        if (!keys.isReady()) {
            return new ImmichStatusDto(false, null, List.of(), null, null, null);
        }
        try {
            final ImmichClient.Storage storage = client.storage();

            return new ImmichStatusDto(true, client.version(), client.queues(), storage.used(), storage.available(), storage.size());
        } catch (final ImmichUnavailableException e) {
            return new ImmichStatusDto(false, null, List.of(), null, null, null);
        }
    }

    public record ImmichStatusDto(boolean available, @Nullable String version, List<ImmichClient.Queue> queues,
                                  @Nullable Long usedBytes, @Nullable Long availableBytes, @Nullable Long sizeBytes) {
    }
}
