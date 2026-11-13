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

package nu.fgv.spexgram.server.spex;

import nu.fgv.spexgram.server.config.CacheConfig;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Spexipedia's GraphQL API: its public base data (spex with their category) and, with {@code SYNC},
 * its change feed.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Component
public class SpexipediaClient {

    static final int PAGE_SIZE = 500;
    private static final String SPEX_FIELDS = "id year title revival retired parent { id } category { id name } poster { pageJpegUrl }";
    private static final String ALL_SPEX = """
            query AllSpex {
                spexAll(includeRetired: true) { %s }
            }""".formatted(SPEX_FIELDS);
    private static final String SPEX_BY_ID = """
            query SpexById($id: ID!) {
                spex(id: $id) { %s }
            }""".formatted(SPEX_FIELDS);
    private static final String CHANGES = """
            query Changes($after: String, $first: Int) {
                baseDataChanges(after: $after, first: $first) {
                    cursor hasMore
                    changes {
                        sequence kind id change mergedIntoId
                        spexCategory { id name }
                        spex { id year title categoryId parentId retired }
                    }
                }
            }""";

    private final HttpSyncGraphQlClient graphQlClient;

    public SpexipediaClient(final HttpSyncGraphQlClient spexipediaGraphQlClient) {
        this.graphQlClient = spexipediaGraphQlClient;
    }

    private static void requireValid(final ClientGraphQlResponse response) {
        if (!response.getErrors().isEmpty() || !response.isValid()) {
            throw new SpexipediaCallException("Response has errors: " + response.getErrors());
        }
    }

    private static <T> T call(final Supplier<T> action) {
        try {
            return action.get();
        } catch (final SpexipediaCallException e) {
            throw e;
        } catch (final RuntimeException e) {
            if (e instanceof RestClientException || e.getClass().getName().startsWith("org.springframework.graphql")) {
                throw new SpexipediaCallException(e.getMessage() == null ? e.getClass().getName() : e.getMessage(), e);
            }
            throw e;
        }
    }

    /**
     * Every spex, originals and revivals, retired ones too, by year.
     */
    @Cacheable(cacheNames = CacheConfig.ALL_SPEX)
    public List<SpexDto> allSpex() {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(ALL_SPEX).executeSync();

            requireValid(response);
            return response.field("spexAll").toEntityList(SpexNode.class).stream()
                    .filter(Objects::nonNull).map(SpexNode::toDto).toList();
        });
    }

    public Optional<SpexDto> spexById(final UUID id) {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(SPEX_BY_ID).variable("id", id).executeSync();

            requireValid(response);
            return Optional.ofNullable(response.field("spex").toEntity(SpexNode.class)).map(SpexNode::toDto);
        });
    }

    /**
     * The changes to base data after the cursor (null for the start).
     */
    public FeedChange.Page changes(final @Nullable String after) {
        return call(() -> {
            final ClientGraphQlResponse response = graphQlClient.document(CHANGES)
                    .variable("after", after).variable("first", PAGE_SIZE).executeSync();

            requireValid(response);
            return Objects.requireNonNull(response.field("baseDataChanges").toEntity(FeedChange.Page.class));
        });
    }

    record IdRef(String id) {
    }

    record CategoryNode(String id, @Nullable String name) {
    }

    record PosterNode(@Nullable String pageJpegUrl) {
    }

    record SpexNode(String id, @Nullable String year, @Nullable String title, @Nullable Boolean revival,
                    @Nullable Boolean retired, @Nullable IdRef parent, @Nullable CategoryNode category,
                    @Nullable PosterNode poster) {

        SpexDto toDto() {
            return new SpexDto(UUID.fromString(id), title, SpexDto.year(year), Boolean.TRUE.equals(revival),
                    parent == null ? null : UUID.fromString(parent.id()),
                    category == null ? null : UUID.fromString(category.id()),
                    category == null ? null : category.name(),
                    Boolean.TRUE.equals(retired), poster == null ? null : poster.pageJpegUrl());
        }
    }

    /**
     * Spexipedia could not be reached or answered with errors.
     */
    public static class SpexipediaCallException extends RuntimeException {

        public SpexipediaCallException(final String message) {
            super(message);
        }

        public SpexipediaCallException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
