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

import java.io.IOException;
import java.io.Serial;

/**
 * A failure that a user will read about later, e.g. why a job failed: carries the text to keep ({@link StoredText}).
 * {@link #text(Throwable)} gives any exception's text, an unexpected one's with its details.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public class TextException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient StoredText text;

    public TextException(final StoredText text) {
        this(text, null);
    }

    public TextException(final StoredText text, final @Nullable Throwable cause) {
        super(text.code() + " " + text.args(), cause);
        this.text = text;
    }

    public StoredText text() {
        return text;
    }

    /**
     * For a method that throws {@link IOException}: one whose cause carries the text.
     */
    public static IOException io(final StoredText text, final @Nullable Throwable cause) {
        return new IOException(text.code() + " " + text.args(), new TextException(text, cause));
    }

    /**
     * The exception's text, or for any other exception "unexpected error" with its details.
     */
    public static StoredText text(final Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TextException failure) {
                return failure.text();
            }
        }
        return StoredText.of("text.unexpected", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
}
