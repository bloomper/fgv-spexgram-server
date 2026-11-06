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

package nu.fgv.spexgram.server.upload;

import jakarta.servlet.http.HttpServletRequest;
import nu.fgv.spexgram.server.user.Caller;
import nu.fgv.spexgram.server.user.CallerService;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * The tus endpoint at {@code /api/uploads}. A thin adapter: {@link UploadService} decides who may upload. The
 * metadata may give {@code filename} and {@code lastModified}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
@RequestMapping("/api/uploads")
public class TusController {

    public static final String TUS_RESUMABLE = "Tus-Resumable";
    public static final String VERSION = "1.0.0";
    private static final String UPLOAD_OFFSET = "Upload-Offset";
    private static final String UPLOAD_LENGTH = "Upload-Length";

    private final UploadService uploadService;
    private final CallerService callerService;

    public TusController(final UploadService uploadService, final CallerService callerService) {
        this.uploadService = uploadService;
        this.callerService = callerService;
    }

    /**
     * A value from tus's {@code Upload-Metadata}: comma-separated {@code key base64value} pairs.
     */
    static @Nullable String metadataValue(final @Nullable String metadata, final String key) {
        if (metadata == null) {
            return null;
        }
        for (final String pair : metadata.split(",")) {
            final String[] parts = pair.strip().split(" ", 2);

            if (parts.length == 2 && parts[0].equals(key)) {
                try {
                    return new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                } catch (final IllegalArgumentException e) {
                    throw new BadRequestException("error.upload.metadata");
                }
            }
        }
        return null;
    }

    /**
     * {@code lastModified} in tus's metadata: the file's last change on the uploader's device, in milliseconds since
     * 1970, as browsers give it.
     */
    static @Nullable Instant lastModified(final @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(value.strip()));
        } catch (final NumberFormatException e) {
            throw new BadRequestException("error.upload.metadata");
        }
    }

    @RequestMapping(method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> options() {
        return ResponseEntity.noContent()
                .header(TUS_RESUMABLE, VERSION)
                .header("Tus-Version", VERSION)
                .header("Tus-Extension", "creation,termination")
                .header("Tus-Max-Size", Long.toString(uploadService.maxSize()))
                .build();
    }

    @PostMapping
    public ResponseEntity<Void> create(@RequestHeader(UPLOAD_LENGTH) final long length,
                                       @RequestHeader(value = "Upload-Metadata", required = false) final @Nullable String metadata) throws IOException {
        final Caller caller = callerService.current();
        final UploadSession session = uploadService.create(caller, length, metadataValue(metadata, "filename"),
                lastModified(metadataValue(metadata, "lastModified")));

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(TUS_RESUMABLE, VERSION)
                .header(HttpHeaders.LOCATION, "/api/uploads/" + session.getId())
                .build();
    }

    @RequestMapping(path = "/{id}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> head(@PathVariable final String id) {
        final UploadSession session = uploadService.get(callerService.current(), id);

        return ResponseEntity.ok()
                .header(TUS_RESUMABLE, VERSION)
                .header(UPLOAD_OFFSET, Long.toString(session.getOffset()))
                .header(UPLOAD_LENGTH, Long.toString(session.getLength()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    @PatchMapping(path = "/{id}", consumes = "application/offset+octet-stream")
    public ResponseEntity<Void> patch(@PathVariable final String id,
                                      @RequestHeader(UPLOAD_OFFSET) final long offset,
                                      final HttpServletRequest request) throws IOException {
        final long newOffset = uploadService.append(callerService.current(), id, offset, request.getInputStream());

        return ResponseEntity.noContent()
                .header(TUS_RESUMABLE, VERSION)
                .header(UPLOAD_OFFSET, Long.toString(newOffset))
                .build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable final String id) throws IOException {
        uploadService.terminate(callerService.current(), id);
        return ResponseEntity.noContent().header(TUS_RESUMABLE, VERSION).build();
    }
}
