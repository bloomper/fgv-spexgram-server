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

package nu.fgv.spexgram.server.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Spexgram's settings under {@code spexgram.*}.
 *
 * @param timeZone the zone schedules run in and "today" is in
 * @param webUrl   the web's address, for links in emails
 * @author Anders Jacobsson
 * @since 1.0
 */
@ConfigurationProperties(prefix = "spexgram")
public record SpexgramProperties(String timeZone,
                                 String webUrl,
                                 Keycloak keycloak,
                                 Security security,
                                 Media media,
                                 Immich immich,
                                 Turnstile turnstile,
                                 Mail mail,
                                 Spexregister spexregister,
                                 Data data) {

    /**
     * @param serviceClientId     {@code fgv-spexgram-service}, which reads editors' email addresses ({@code view-users})
     * @param serviceClientSecret its secret
     * @param adminClientId       {@code fgv-admin}, only for the sample data import, which grants roles
     * @param adminClientSecret   its secret
     */
    public record Keycloak(String url, String realm, String serviceClientId, String serviceClientSecret,
                           String adminClientId, String adminClientSecret) {
    }

    /**
     * @param authorizedParties the clients ({@code azp}) whose tokens the API accepts
     * @param clientIpSecret    the secret shared with the web, which sends it with the visitor's address; without it,
     *                          or when it doesn't match, the API ignores the address the caller names
     */
    public record Security(List<String> authorizedParties, String clientIpSecret) {
    }

    /**
     * @param baseUrl       the media hostname's address, e.g. {@code https://media.example.org}
     * @param signingKey    the secret shared with the media server (nginx {@code secure_link})
     * @param linkValidity  how long a signed address is valid
     * @param uploads       the folder of unfinished uploads
     * @param maxUploadSize the largest file the deployment accepts; admins can lower it
     */
    public record Media(String baseUrl, String signingKey, Duration linkValidity, Path uploads, DataSize maxUploadSize) {
    }

    /**
     * @param url           Immich on the internal network, e.g. {@code http://immich-server:2283}
     * @param adminEmail    the email of Immich's admin, Spexgram's system user
     * @param adminPassword its password, from the deployment's secrets
     * @param keysDir       where the API's and the bulk import's API keys are kept
     * @param mediaKeyDir   where the media server's read-only key ({@code media.key}) is kept, alone, so that the media
     *                      server mounts only it; the keys folder when not set
     * @param clipModel     the multilingual CLIP model for search by content
     * @param startupWait   how long the API waits for Immich at start before it keeps trying in the background
     */
    public record Immich(String url, String adminEmail, String adminPassword, Path keysDir, @Nullable Path mediaKeyDir, String clipModel,
                         Duration startupWait) {
    }

    /**
     * @param secretKey Cloudflare Turnstile's secret, for visitors' reports
     * @param verifyUrl Cloudflare's siteverify endpoint
     */
    public record Turnstile(String secretKey, String verifyUrl) {
    }

    public record Mail(String from) {
    }

    public record Spexregister(String graphqlUrl) {
    }

    public record Data(boolean importSampleData, String sampleDataSize, String sampleUserPassword) {
    }
}
