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

package nu.fgv.spexgram.server.util.graphql;

import graphql.GraphQLError;
import graphql.execution.ResultPath;
import graphql.schema.DataFetchingEnvironment;
import jakarta.persistence.OptimisticLockException;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.util.error.ConflictException;
import nu.fgv.spexgram.server.util.error.DirectoryUnavailableException;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import nu.fgv.spexgram.server.util.error.SpexregisterUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Exceptions as GraphQL errors, with a classification and a message in the request's language.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionResolverTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private DataFetchingEnvironment environment;
    private GlobalExceptionResolver resolver;

    @BeforeEach
    void setUp() {
        final StaticMessageSource messages = new StaticMessageSource();

        messages.addMessage("error.forbidden", Locale.of("sv"), "Du har inte behörighet att göra det.");
        messages.addMessage("error.forbidden", Locale.ENGLISH, "You are not allowed to do that.");
        messages.addMessage("error.album.notFound", Locale.of("sv"), "Albumet {0} finns inte.");
        resolver = new GlobalExceptionResolver(messages);
        lenient().when(environment.getExecutionStepInfo().getPath()).thenReturn(ResultPath.rootPath());
        lenient().when(environment.getLocale()).thenReturn(Locale.of("sv"));
    }

    private GraphQLError resolve(final Throwable ex) {
        return resolver.resolveToSingleError(ex, environment);
    }

    @Test
    void eachExceptionHasItsClassification() {
        assertThat(resolve(new AccessDeniedException("x")).getErrorType().toString()).isEqualTo("FORBIDDEN");
        assertThat(resolve(new AuthenticationCredentialsNotFoundException("x")).getErrorType().toString()).isEqualTo("UNAUTHORIZED");
        assertThat(resolve(new BadRequestException("error.x")).getErrorType().toString()).isEqualTo("BAD_REQUEST");
        assertThat(resolve(new ResourceNotFoundException("error.album.notFound", 7)).getErrorType().toString()).isEqualTo("NOT_FOUND");
        assertThat(resolve(new ConflictException("error.x")).getErrorType().toString()).isEqualTo("CONFLICT");
        assertThat(resolve(new OptimisticLockingFailureException("x")).getErrorType().toString()).isEqualTo("CONFLICT");
        assertThat(resolve(new OptimisticLockException("x")).getErrorType().toString()).isEqualTo("CONFLICT");
        assertThat(resolve(new SpexregisterUnavailableException(new RuntimeException())).getErrorType().toString()).isEqualTo("SPEXREGISTER_UNAVAILABLE");
        assertThat(resolve(new DirectoryUnavailableException(new RuntimeException())).getErrorType().toString()).isEqualTo("DIRECTORY_UNAVAILABLE");
        assertThat(resolve(new IllegalStateException("bug"))).isNull();
    }

    @Test
    void theMessageIsInTheRequestsLanguageWithItsArguments() {
        assertThat(resolve(new ResourceNotFoundException("error.album.notFound", 7)).getMessage()).isEqualTo("Albumet 7 finns inte.");

        lenient().when(environment.getLocale()).thenReturn(Locale.ENGLISH);
        assertThat(resolve(new AccessDeniedException("x")).getMessage()).isEqualTo("You are not allowed to do that.");
    }

    @Test
    void anUnknownMessageCodeIsShownAsIs() {
        assertThat(resolve(new BadRequestException("error.unknown")).getMessage()).isEqualTo("error.unknown");
    }
}
