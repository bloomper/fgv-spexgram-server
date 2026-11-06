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

package nu.fgv.spexgram.server.util.web;

import jakarta.persistence.OptimisticLockException;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Locale;

/**
 * Errors of the plain HTTP endpoints as {@code {code, message}} JSON, with the codes and messages GraphQL uses.
 * GraphQL has its own resolver.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestControllerAdvice
public class HttpErrorAdvice {

    private final MessageSource messageSource;

    public HttpErrorAdvice(final MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Error> badRequest(final BadRequestException e, final Locale locale) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getDetailMessageCode(), e.getDetailMessageArguments(), locale);
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Error> missing(final Exception e, final Locale locale) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "error.upload.empty", null, locale);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Error> tooLarge(final MaxUploadSizeExceededException e, final Locale locale) {
        return error(HttpStatus.CONTENT_TOO_LARGE, "BAD_REQUEST", "error.upload.tooLarge", null, locale);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Error> notFound(final ResourceNotFoundException e, final Locale locale) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getDetailMessageCode(), e.getDetailMessageArguments(), locale);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Error> conflict(final ConflictException e, final Locale locale) {
        return error(HttpStatus.CONFLICT, "CONFLICT", e.getDetailMessageCode(), e.getDetailMessageArguments(), locale);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<Error> staleVersion(final Exception e, final Locale locale) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "error.conflict", null, locale);
    }

    @ExceptionHandler(ImmichUnavailableException.class)
    public ResponseEntity<Error> immichUnavailable(final ImmichUnavailableException e, final Locale locale) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "IMMICH_UNAVAILABLE", ImmichUnavailableException.MESSAGE_CODE, null, locale);
    }

    @ExceptionHandler(SpexregisterUnavailableException.class)
    public ResponseEntity<Error> spexregisterUnavailable(final SpexregisterUnavailableException e, final Locale locale) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SPEXREGISTER_UNAVAILABLE", SpexregisterUnavailableException.MESSAGE_CODE, null, locale);
    }

    @ExceptionHandler(DirectoryUnavailableException.class)
    public ResponseEntity<Error> directoryUnavailable(final DirectoryUnavailableException e, final Locale locale) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DIRECTORY_UNAVAILABLE", DirectoryUnavailableException.MESSAGE_CODE, null, locale);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Error> forbidden(final AccessDeniedException e, final Locale locale) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "error.forbidden", null, locale);
    }

    private ResponseEntity<Error> error(final HttpStatus status, final String code, final @Nullable String messageCode,
                                        final Object @Nullable [] arguments, final Locale locale) {
        final String key = messageCode == null ? "error.forbidden" : messageCode;
        final String message = messageSource.getMessage(key, arguments, key, Locales.supported(locale));

        return ResponseEntity.status(status).body(new Error(code, message == null ? key : message));
    }

    public record Error(String code, String message) {
    }
}
