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

import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import nu.fgv.spexgram.server.immich.ImmichUnavailableException;
import nu.fgv.spexgram.server.util.Locales;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.util.error.ConflictException;
import nu.fgv.spexgram.server.util.error.DirectoryUnavailableException;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import nu.fgv.spexgram.server.util.error.SpexregisterUnavailableException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * Maps exceptions to the API's error codes, with messages in the request's language.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class GlobalExceptionResolver extends DataFetcherExceptionResolverAdapter {

    private final MessageSource messageSource;

    public GlobalExceptionResolver(final MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private static GraphQLError error(final ErrorClassification type, final @Nullable String message, final DataFetchingEnvironment environment) {
        return GraphQLError.newError()
                .errorType(type)
                .message(message)
                .path(environment.getExecutionStepInfo().getPath())
                .location(environment.getField().getSourceLocation())
                .build();
    }

    @Override
    protected @Nullable List<GraphQLError> resolveToMultipleErrors(final Throwable ex, final DataFetchingEnvironment environment) {
        if (ex instanceof final ConstraintViolationException e) {
            return e.getConstraintViolations().stream()
                    .map(violation -> error(ErrorType.BAD_REQUEST, violation.getMessage(), environment))
                    .toList();
        }

        final GraphQLError error = resolveToSingleError(ex, environment);

        return error != null ? List.of(error) : null;
    }

    @Override
    protected @Nullable GraphQLError resolveToSingleError(final Throwable ex, final DataFetchingEnvironment environment) {
        return switch (ex) {
            case final AccessDeniedException _ -> localized(ErrorType.FORBIDDEN, "error.forbidden", null, environment);
            case final AuthenticationException _ ->
                    localized(ErrorType.UNAUTHORIZED, "error.unauthorized", null, environment);
            case final BadRequestException e -> localized(ErrorType.BAD_REQUEST, e, environment);
            case final ResourceNotFoundException e -> localized(ErrorType.NOT_FOUND, e, environment);
            case final ConflictException e -> localized(CustomErrorType.CONFLICT, e, environment);
            case final ImmichUnavailableException e -> localized(CustomErrorType.IMMICH_UNAVAILABLE, e, environment);
            case final SpexregisterUnavailableException e ->
                    localized(CustomErrorType.SPEXREGISTER_UNAVAILABLE, e, environment);
            case final DirectoryUnavailableException e ->
                    localized(CustomErrorType.DIRECTORY_UNAVAILABLE, e, environment);
            case final OptimisticLockingFailureException _, final OptimisticLockException _ ->
                    localized(CustomErrorType.CONFLICT, "error.conflict", null, environment);
            default -> null;
        };
    }

    private GraphQLError localized(final ErrorClassification type, final ResponseStatusException e, final DataFetchingEnvironment environment) {
        return localized(type, e.getDetailMessageCode(), e.getDetailMessageArguments(), environment);
    }

    private GraphQLError localized(final ErrorClassification type, final String code, final Object @Nullable [] arguments, final DataFetchingEnvironment environment) {
        final Locale locale = Locales.supported(environment.getLocale());

        return error(type, messageSource.getMessage(code, arguments, code, locale), environment);
    }

}
