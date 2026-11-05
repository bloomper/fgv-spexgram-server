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

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Swedish first, English when asked for.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public final class Locales {

    public static final Locale SWEDISH = Locale.of("sv");

    private Locales() {
    }

    public static Locale supported(final @Nullable Locale requested) {
        return requested != null && Locale.ENGLISH.getLanguage().equals(requested.getLanguage()) ? Locale.ENGLISH : SWEDISH;
    }
}
