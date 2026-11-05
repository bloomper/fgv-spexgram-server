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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Repository for {@link UploadSession}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface UploadSessionRepository extends JpaRepository<UploadSession, String> {

    List<UploadSession> findByCreatedAtBefore(Instant before);

    /**
     * The declared length of the owner's open uploads, whenever they were started: they are deleted once used or after
     * 24 hours.
     */
    @Query("SELECT COALESCE(SUM(s.length), 0) FROM UploadSession s WHERE s.owner = :owner")
    long sumLengthByOwner(@Param("owner") String owner);

    long countByOwner(String owner);
}
