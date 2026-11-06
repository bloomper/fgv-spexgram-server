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


package nu.fgv.spexgram.server.album;


import nu.fgv.spexgram.server.album.AlbumKindService.AlbumKindDto;
import nu.fgv.spexgram.server.album.AlbumKindService.AlbumKindInput;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * GraphQL for the album kinds. A thin adapter: {@link AlbumKindService} decides access.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class AlbumKindGraphqlApi {

    private final AlbumKindService service;

    public AlbumKindGraphqlApi(final AlbumKindService service) {
        this.service = service;
    }

    private static <T> Map<AlbumKindDto, T> byKind(final List<AlbumKindDto> kinds, final Function<List<UUID>, Map<UUID, T>> lookup) {
        final Map<UUID, T> values = lookup.apply(kinds.stream().map(AlbumKindDto::id).toList());
        final Map<AlbumKindDto, T> result = new LinkedHashMap<>();

        kinds.forEach(kind -> result.put(kind, values.get(kind.id())));
        return result;
    }

    @QueryMapping
    public List<AlbumKindDto> albumKinds() {
        return service.kinds();
    }

    @BatchMapping(typeName = "AlbumKind", field = "isDefault")
    public Map<AlbumKindDto, Boolean> isDefault(final List<AlbumKindDto> kinds) {
        return byKind(kinds, service::defaults);
    }

    @BatchMapping(typeName = "AlbumKind", field = "albumCount")
    public Map<AlbumKindDto, Integer> albumCount(final List<AlbumKindDto> kinds) {
        return byKind(kinds, service::albumCounts);
    }

    @MutationMapping
    public AlbumKindDto albumKindCreate(@Argument final AlbumKindInput input) {
        return service.create(input);
    }

    @MutationMapping
    public AlbumKindDto albumKindUpdate(@Argument final UUID id, @Argument final AlbumKindInput input) {
        return service.update(id, input);
    }

    @MutationMapping
    public List<AlbumKindDto> albumKindsReorder(@Argument final List<UUID> ids) {
        return service.reorder(ids);
    }

    @MutationMapping
    public @Nullable Void albumKindDelete(@Argument final UUID id) {
        service.delete(id);
        return null;
    }
}
