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


package nu.fgv.spexgram.server.visibility;

import org.junit.jupiter.api.Test;

import static nu.fgv.spexgram.server.visibility.Visibility.MEMBERS;
import static nu.fgv.spexgram.server.visibility.Visibility.PUBLIC;
import static nu.fgv.spexgram.server.visibility.Visibility.UNPUBLISHED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The effective visibility of an item, 4.1 of the spec.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
class VisibilityTest {

    @Test
    void anItemIsSeenWithTheNarrowerOfItsOwnAndItsAlbums() {
        assertThat(Visibility.effective(PUBLIC, false, MEMBERS, false)).isEqualTo(MEMBERS);
        assertThat(Visibility.effective(MEMBERS, false, MEMBERS, false)).isEqualTo(MEMBERS);
        assertThat(Visibility.effective(PUBLIC, false, UNPUBLISHED, false)).isEqualTo(UNPUBLISHED);
        assertThat(Visibility.effective(PUBLIC, false, PUBLIC, false)).isEqualTo(PUBLIC);
    }

    @Test
    void aPublicItemIsPublicOnItsOwnInAnAlbumForMembers() {
        assertThat(Visibility.effective(MEMBERS, false, PUBLIC, false)).isEqualTo(PUBLIC);
    }

    @Test
    void nothingInAnUnpublishedOrTrashedAlbumNorTrashedOrUnsortedIsShownOutsideTheEditors() {
        assertThat(Visibility.effective(UNPUBLISHED, false, PUBLIC, false)).isEqualTo(UNPUBLISHED);
        assertThat(Visibility.effective(PUBLIC, true, PUBLIC, false)).isEqualTo(UNPUBLISHED);
        assertThat(Visibility.effective(PUBLIC, false, PUBLIC, true)).isEqualTo(UNPUBLISHED);
        assertThat(Visibility.effective(null, false, PUBLIC, false)).isEqualTo(UNPUBLISHED);
    }

    @Test
    void visitorsSeePublicMembersAndSpexipediaAlsoMembersEditorsEverything() {
        assertThat(Viewer.visitor().sees()).containsExactly(PUBLIC);
        assertThat(new Viewer(Viewer.Kind.SPEXIPEDIA, null).sees()).containsExactlyInAnyOrder(MEMBERS, PUBLIC);
        assertThat(new Viewer(Viewer.Kind.USER, null).sees()).containsExactlyInAnyOrder(MEMBERS, PUBLIC);
        assertThat(new Viewer(Viewer.Kind.EDITOR, null).sees()).containsExactlyInAnyOrder(UNPUBLISHED, MEMBERS, PUBLIC);
    }
}
