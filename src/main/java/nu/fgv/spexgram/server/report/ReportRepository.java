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


package nu.fgv.spexgram.server.report;

import nu.fgv.spexgram.server.item.Item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link Report}.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public interface ReportRepository extends JpaRepository<Report, Long> {

    Optional<Report> findByUuid(UUID uuid);

    List<Report> findByStatusOrderByCreatedAtAsc(Report.Status status);

    List<Report> findByStatusOrderByHandledAtDesc(Report.Status status);

    List<Report> findByReporterSubjectOrderByCreatedAtDesc(String subject);

    List<Report> findAllByOrderByCreatedAtDesc();

    List<Report> findByItemAndStatus(Item item, Report.Status status);

    long deleteByHandledAtBefore(Instant before);
}
