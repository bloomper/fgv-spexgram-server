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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * An upload in progress or finished but not yet used. Deleted when used, or after 24 hours.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Entity
@Table(name = "upload_session")
@Getter
@Setter
public class UploadSession {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "owner", nullable = false)
    private String owner;

    @Column(name = "length", nullable = false)
    private long length;

    @Column(name = "upload_offset", nullable = false)
    private long offset;

    @Column(name = "file_name")
    private @Nullable String fileName;

    /**
     * When the file was last changed on the uploader's device, from tus's metadata; Immich falls back to it when the
     * file has no date taken.
     */
    @Column(name = "last_modified")
    private @Nullable Instant lastModified;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    public boolean isComplete() {
        return offset == length;
    }
}
