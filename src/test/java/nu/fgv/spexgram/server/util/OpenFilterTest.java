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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenFilterTest {

    @Test
    void nullAsksForAll() {
        assertThat(OpenFilter.wanted(null, true)).isTrue();
        assertThat(OpenFilter.wanted(null, false)).isTrue();
    }

    @Test
    void trueAsksForOpenOnesAndFalseForHandledOnes() {
        assertThat(OpenFilter.wanted(true, true)).isTrue();
        assertThat(OpenFilter.wanted(true, false)).isFalse();
        assertThat(OpenFilter.wanted(false, false)).isTrue();
        assertThat(OpenFilter.wanted(false, true)).isFalse();
    }
}
