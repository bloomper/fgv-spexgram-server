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

package nu.fgv.spexgram.server.util;

import nu.fgv.spexgram.server.user.CallerService;
import nu.fgv.spexgram.server.util.docs.SecuritySnippet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpHeaders;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.headers.RequestHeadersSnippet;
import org.springframework.restdocs.payload.ResponseFieldsSnippet;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;

/**
 * Base of the tests that document the plain HTTP endpoints with Spring REST Docs. Security is left out of MockMvc;
 * {@link #security} documents what the endpoint requires.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(RestDocumentationExtension.class)
@DisabledInAotMode
public abstract class AbstractApiTest {

    protected static final String BEARER = "Bearer <access token>";

    protected static final RequestHeadersSnippet secureRequestHeaders = requestHeaders(
            headerWithName(HttpHeaders.AUTHORIZATION).description("The authorization header")
    );

    protected static final ResponseFieldsSnippet errorResponseFields = responseFields(
            fieldWithPath("code").description("The error code, e.g. `NOT_FOUND`, `BAD_REQUEST`, `FORBIDDEN` or `CONFLICT`"),
            fieldWithPath("message").description("A human-readable explanation, in the request's language")
    );

    protected MockMvc mockMvc;

    @MockitoBean
    protected CallerService callerService;

    protected static SecuritySnippet noSecurity() {
        return new SecuritySnippet(false, List.of());
    }

    protected static SecuritySnippet security(final List<String> roles) {
        return new SecuritySnippet(true, roles);
    }

    @BeforeEach
    public void setUp(final WebApplicationContext webApplicationContext, final RestDocumentationContextProvider restDocumentation) {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(documentationConfiguration(restDocumentation)
                        .uris()
                        .withHost("api.spexgram.fgv.local.nu")
                        .withPort(8443)
                        .withScheme("https"))
                .build();
    }
}
