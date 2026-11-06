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

import nu.fgv.spexgram.server.user.Caller;
import nu.fgv.spexgram.server.user.Role;
import nu.fgv.spexgram.server.util.AbstractApiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.restdocs.request.PathParametersSnippet;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.head;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.options;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.patch;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documents the tus upload endpoint.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@WebMvcTest(TusController.class)
class TusHttpApiTest extends AbstractApiTest {

    private static final String ID = "8f14e45f-ceea-467f-a0e6-1b2c3d4e5f60";

    private final PathParametersSnippet uploadId = pathParameters(parameterWithName("id").description("The id of the upload"));

    @MockitoBean
    private UploadService uploadService;

    private Caller caller;

    private static UploadSession session(final long offset) {
        final UploadSession session = new UploadSession();

        session.setId(ID);
        session.setOwner("subject");
        session.setLength(52_428_800L);
        session.setOffset(offset);
        return session;
    }

    @BeforeEach
    void setUpCaller() {
        caller = new Caller("subject", "editor@fgv.nu", "Editor", "editor@fgv.nu", Set.of(Role.USER, Role.EDITOR));
        when(callerService.current()).thenReturn(caller);
    }

    @Test
    void describesTheServer() throws Exception {
        when(uploadService.maxSize()).thenReturn(53_687_091_200L);

        mockMvc
                .perform(options("/api/uploads"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Tus-Max-Size", "53687091200"))
                .andDo(document(
                        "upload-options",
                        responseHeaders(
                                headerWithName("Tus-Resumable").description("The tus version, `1.0.0`"),
                                headerWithName("Tus-Version").description("The tus versions the server speaks"),
                                headerWithName("Tus-Extension").description("`creation,termination`"),
                                headerWithName("Tus-Max-Size").description("The largest file accepted, in bytes")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void createsAnUpload() throws Exception {
        final UploadSession session = session(0);

        when(uploadService.create(eq(caller), eq(52_428_800L), eq("Premiären 2019.jpg"), eq(Instant.ofEpochMilli(1_550_175_300_000L)))).thenReturn(session);

        mockMvc
                .perform(post("/api/uploads")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Length", "52428800")
                        .header("Upload-Metadata", "filename UHJlbWnDpHJlbiAyMDE5LmpwZw==,lastModified MTU1MDE3NTMwMDAwMA=="))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/uploads/" + ID))
                .andDo(document(
                        "upload-post",
                        requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION).description("The authorization header"),
                                headerWithName("Tus-Resumable").description("`1.0.0`"),
                                headerWithName("Upload-Length").description("The size of the file, in bytes"),
                                headerWithName("Upload-Metadata").description("`filename`, and `lastModified` in milliseconds since 1970, each in base64; optional").optional()
                        ),
                        responseHeaders(
                                headerWithName("Tus-Resumable").description("`1.0.0`"),
                                headerWithName(HttpHeaders.LOCATION).description("The upload's address, for the following requests")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void tellsTheOffset() throws Exception {
        when(uploadService.get(caller, ID)).thenReturn(session(10_485_760));

        mockMvc
                .perform(head("/api/uploads/{id}", ID).header(HttpHeaders.AUTHORIZATION, BEARER).header("Tus-Resumable", "1.0.0"))
                .andExpect(status().isOk())
                .andExpect(header().string("Upload-Offset", "10485760"))
                .andDo(document(
                        "upload-head",
                        uploadId,
                        secureRequestHeaders,
                        responseHeaders(
                                headerWithName("Tus-Resumable").description("`1.0.0`"),
                                headerWithName("Upload-Offset").description("How many bytes the server has"),
                                headerWithName("Upload-Length").description("The size of the file, in bytes"),
                                headerWithName(HttpHeaders.CACHE_CONTROL).description("`no-store`")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void appendsAChunk() throws Exception {
        when(uploadService.append(eq(caller), eq(ID), eq(0L), any())).thenReturn(4L);

        mockMvc
                .perform(patch("/api/uploads/{id}", ID)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Tus-Resumable", "1.0.0")
                        .header("Upload-Offset", "0")
                        .contentType("application/offset+octet-stream")
                        .content(new byte[]{0x1a, 0x45, (byte) 0xdf, (byte) 0xa3}))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Upload-Offset", "4"))
                .andDo(document(
                        "upload-patch",
                        uploadId,
                        requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION).description("The authorization header"),
                                headerWithName("Tus-Resumable").description("`1.0.0`"),
                                headerWithName("Upload-Offset").description("Where the chunk starts; must be the server's offset"),
                                headerWithName(HttpHeaders.CONTENT_TYPE).description("`application/offset+octet-stream`")
                        ),
                        responseHeaders(
                                headerWithName("Tus-Resumable").description("`1.0.0`"),
                                headerWithName("Upload-Offset").description("The new offset")
                        ),
                        security(List.of("USER"))
                ));
    }

    @Test
    void terminatesAnUpload() throws Exception {
        mockMvc
                .perform(delete("/api/uploads/{id}", ID).header(HttpHeaders.AUTHORIZATION, BEARER).header("Tus-Resumable", "1.0.0"))
                .andExpect(status().isNoContent())
                .andDo(document(
                        "upload-delete",
                        uploadId,
                        secureRequestHeaders,
                        security(List.of("USER"))
                ));
    }
}
