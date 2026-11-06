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

import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.immich.ImmichClient;
import nu.fgv.spexgram.server.util.AbstractApiTest;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static nu.fgv.spexgram.server.util.TestUuids.uuid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documents the zip of an album and the errors of the plain HTTP endpoints.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@WebMvcTest(DownloadController.class)
class DownloadHttpApiTest extends AbstractApiTest {

    private static final UUID ALBUM = uuid(1);

    @MockitoBean
    private DownloadService downloadService;
    @MockitoBean
    private ImmichClient immichClient;

    @Test
    void downloadsAnAlbumAsAZip() throws Exception {
        when(downloadService.album(ALBUM)).thenReturn(new DownloadService.Download("Cleopatra 2024 – föreställning", List.of(uuid(2))));
        doAnswer(invocation -> {
            invocation.<OutputStream>getArgument(1).write("PK".getBytes(StandardCharsets.US_ASCII));
            return null;
        }).when(immichClient).downloadArchive(eq(List.of(uuid(2))), any());

        mockMvc
                .perform(get("/api/albums/{id}/zip", ALBUM).header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andDo(document(
                        "album-zip",
                        pathParameters(parameterWithName("id").description("The id of the album")),
                        secureRequestHeaders,
                        responseHeaders(
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("`application/zip`"),
                                headerWithName(HttpHeaders.CONTENT_DISPOSITION).description("`attachment` with the album's title as the file name")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void anAlbumTheUserMayNotSeeIsNotFound() throws Exception {
        when(downloadService.album(ALBUM)).thenThrow(new ResourceNotFoundException(Album.class, ALBUM));

        mockMvc
                .perform(get("/api/albums/{id}/zip", ALBUM).header(HttpHeaders.AUTHORIZATION, BEARER).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isNotFound())
                .andDo(document(
                        "error",
                        errorResponseFields
                ));
    }
}
