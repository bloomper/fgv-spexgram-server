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

import jakarta.servlet.http.HttpServletResponse;
import nu.fgv.spexgram.server.immich.ImmichClient;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * An album as a zip of its originals, made by Immich's download endpoint and streamed through the API, which checks
 * access first ({@link DownloadService}).
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class DownloadController {

    private final DownloadService downloadService;
    private final ImmichClient immichClient;

    public DownloadController(final DownloadService downloadService, final ImmichClient immichClient) {
        this.downloadService = downloadService;
        this.immichClient = immichClient;
    }

    @GetMapping("/api/albums/{id}/zip")
    public void zip(@PathVariable final UUID id, final HttpServletResponse response) throws IOException {
        final DownloadService.Download download = downloadService.album(id);

        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(download.title() + ".zip", StandardCharsets.UTF_8).build().toString());
        immichClient.downloadArchive(download.assets(), response.getOutputStream());
    }
}
