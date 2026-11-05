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


package nu.fgv.spexgram.server.contribution;

import nu.fgv.spexgram.server.album.AlbumDtos.AlbumDto;
import nu.fgv.spexgram.server.album.AlbumService;
import nu.fgv.spexgram.server.contribution.ContributionService.AlbumSuggestionDto;
import nu.fgv.spexgram.server.contribution.ContributionService.AlbumSuggestionInput;
import nu.fgv.spexgram.server.contribution.ContributionService.ContributionReviewDto;
import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.item.ItemDtos.UploadResult;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

/**
 * GraphQL for contributions and album suggestions. A thin adapter: {@link ContributionService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class ContributionGraphqlApi {

    private final ContributionService service;
    private final AlbumService albumService;

    public ContributionGraphqlApi(final ContributionService service, final AlbumService albumService) {
        this.service = service;
        this.albumService = albumService;
    }

    @QueryMapping
    public List<ItemDto> myContributions() {
        return service.mine();
    }

    @QueryMapping
    public List<ItemDto> contributions(@Argument final @Nullable UUID albumId) {
        return service.waiting(albumId);
    }

    @QueryMapping
    public List<AlbumSuggestionDto> albumSuggestions(@Argument final @Nullable Boolean open, @Argument final @Nullable Boolean mine) {
        return service.suggestions(open, Boolean.TRUE.equals(mine));
    }

    @SchemaMapping(typeName = "AlbumSuggestion", field = "album")
    public @Nullable AlbumDto album(final AlbumSuggestionDto suggestion) {
        return suggestion.albumId() == null ? null : albumService.album(suggestion.albumId());
    }

    @MutationMapping
    public List<UploadResult> contributionsAdd(@Argument final UUID albumId, @Argument final List<String> uploadIds) {
        return service.contribute(albumId, uploadIds);
    }

    @MutationMapping
    public @Nullable Void contributionWithdraw(@Argument final UUID itemId) {
        service.withdraw(itemId);
        return null;
    }

    @MutationMapping
    public List<ContributionReviewDto> contributionsReview(@Argument final List<UUID> itemIds, @Argument final boolean accept,
                                                           @Argument final @Nullable String message) {
        return service.review(itemIds, accept, message);
    }

    @MutationMapping
    public AlbumSuggestionDto albumSuggest(@Argument final AlbumSuggestionInput input) {
        return service.suggest(input);
    }

    @MutationMapping
    public AlbumSuggestionDto albumSuggestionReview(@Argument final UUID id, @Argument final boolean accept,
                                                    @Argument final @Nullable String message) {
        return service.reviewSuggestion(id, accept, message);
    }
}
