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


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import nu.fgv.spexgram.server.util.AbstractAuditable;
import nu.fgv.spexgram.server.util.Uuids;

import java.util.UUID;

/**
 * What an album is of, e.g. a show, a rehearsal or a party. Admins configure the kinds, with a Swedish and an English
 * name; the first by
 * position is the default for new albums.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "album_kind")
@Getter
@Setter
public class AlbumKind extends AbstractAuditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, updatable = false, length = 36)
    private UUID uuid = Uuids.v7();

    @Column(name = "name_sv", nullable = false)
    private String nameSv;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "position", nullable = false)
    private int position;
}
