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


package nu.fgv.spexgram.server.user;

import org.jspecify.annotations.Nullable;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * GraphQL for the caller.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Controller
public class MeGraphqlApi {

    private final CallerService callerService;

    public MeGraphqlApi(final CallerService callerService) {
        this.callerService = callerService;
    }

    /**
     * The caller with a token, or null for a visitor without one.
     */
    @QueryMapping
    public @Nullable MeDto me() {
        return callerService.currentIfAny().map(caller -> {
            if (caller.isUser()) {
                callerService.record(caller);
            }
            return new MeDto(caller.username(), caller.displayName(), caller.email(),
                    caller.roles().stream().map(Enum::name).sorted().toList());
        }).orElse(null);
    }

    public record MeDto(String username, @Nullable String displayName, @Nullable String email, List<String> roles) {
    }
}
