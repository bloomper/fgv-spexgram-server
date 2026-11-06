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

package nu.fgv.spexgram.server.upload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TusControllerTest {

    @Test
    void readsBase64ValuesFromUploadMetadata() {
        final String metadata = "filename MDEgw5ZwcG5pbmcuZmxhYw==,filetype YXVkaW8vZmxhYw==";

        assertThat(TusController.metadataValue(metadata, "filename")).isEqualTo("01 Öppning.flac");
        assertThat(TusController.metadataValue(metadata, "filetype")).isEqualTo("audio/flac");
        assertThat(TusController.metadataValue(metadata, "other")).isNull();
        assertThat(TusController.metadataValue(null, "filename")).isNull();
    }
}
