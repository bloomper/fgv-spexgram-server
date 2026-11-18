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

package nu.fgv.spexgram.server.imports;

import jakarta.servlet.http.HttpServletRequest;
import nu.fgv.spexgram.server.user.CallerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * The import API's files (Spexipedia's docs/content-import.md, section 4): {@code HEAD} tells whether a file with the hash is here, {@code PUT} uploads it.
 * {@code IMPORT} only.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class ImportFileController {

    private final ImportFileStore store;
    private final CallerService callerService;

    public ImportFileController(final ImportFileStore store, final CallerService callerService) {
        this.store = store;
        this.callerService = callerService;
    }

    @RequestMapping(path = "/api/import/files/{sha256}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> has(@PathVariable final String sha256) {
        callerService.current().requireImport();
        return store.has(ImportFileStore.normalize(sha256)) ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @RequestMapping(path = "/api/import/files/{sha256}", method = RequestMethod.PUT)
    public ResponseEntity<Void> put(@PathVariable final String sha256, final HttpServletRequest request) throws IOException {
        callerService.current().requireImport();
        return ResponseEntity.status(store.put(sha256, request.getInputStream()) ? HttpStatus.CREATED : HttpStatus.OK).build();
    }
}
