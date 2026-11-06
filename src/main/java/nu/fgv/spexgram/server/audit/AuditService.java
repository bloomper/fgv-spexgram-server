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

package nu.fgv.spexgram.server.audit;

import jakarta.persistence.criteria.Predicate;
import nu.fgv.spexgram.server.user.Caller;
import nu.fgv.spexgram.server.user.CallerService;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Writes and reads the audit log. Editors see their own changes, admins everyone's.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class AuditService {

    private static final int MAX_SUMMARY = 1000;
    private static final int MAX_PAGE = 200;

    private final AuditEntryRepository repository;
    private final CallerService callerService;
    private final Clock clock;

    public AuditService(final AuditEntryRepository repository, final CallerService callerService, final Clock clock) {
        this.repository = repository;
        this.callerService = callerService;
        this.clock = clock;
    }

    private static Specification<AuditEntry> specification(final AuditFilter filter, final Caller caller) {
        return (root, query, builder) -> {
            final List<Predicate> predicates = new ArrayList<>();

            if (!caller.isAdmin()) {
                predicates.add(builder.equal(root.get("actorSubject"), caller.subject()));
            } else if (StringUtils.hasText(filter.actorId())) {
                predicates.add(builder.equal(root.get("actorSubject"), filter.actorId()));
            }
            if (StringUtils.hasText(filter.entityType())) {
                predicates.add(builder.equal(root.get("entityType"), filter.entityType()));
            }
            if (StringUtils.hasText(filter.action())) {
                predicates.add(builder.equal(root.get("action"), filter.action()));
            }
            if (StringUtils.hasText(filter.entityId())) {
                predicates.add(builder.equal(root.get("entityId"), filter.entityId()));
            }
            if (filter.from() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("at"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(builder.lessThan(root.get("at"), filter.to()));
            }
            if (StringUtils.hasText(filter.q())) {
                final String pattern = "%" + filter.q().strip().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";

                predicates.add(builder.like(builder.lower(root.get("summary")), pattern, '\\'));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    @Transactional
    public void record(final String action, final String entityType, final @Nullable UUID entityId, final String summary) {
        final AuditEntry entry = new AuditEntry();
        final Caller caller = callerService.currentIfAny().orElse(null);

        entry.setAt(clock.instant());
        entry.setActorSubject(caller == null ? "system" : caller.subject());
        entry.setActorName(caller == null ? "Spexgram" : caller.displayName());
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId == null ? null : entityId.toString());
        entry.setSummary(summary.length() > MAX_SUMMARY ? summary.substring(0, MAX_SUMMARY) : summary);
        repository.save(entry);
    }

    @Transactional(readOnly = true)
    public Page<AuditEntry> page(final int page, final int size) {
        return page(page, size, new AuditFilter(null, null, null, null, null, null, null));
    }

    /**
     * A page of the audit log, newest first: the caller's own changes, or everyone's for ADMIN.
     */
    @Transactional(readOnly = true)
    public Page<AuditEntry> page(final int page, final int size, final @Nullable AuditFilter filter) {
        final Caller caller = callerService.current();
        final PageRequest request = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE), Sort.by(Sort.Order.desc("at"), Sort.Order.desc("id")));

        caller.requireEditor();
        return repository.findAll(specification(filter == null ? new AuditFilter(null, null, null, null, null, null, null) : filter, caller), request);
    }

    /**
     * Who made the changes the caller may see, by subject with their latest name: everyone for ADMIN, else the caller.
     */
    @Transactional(readOnly = true)
    public Map<String, @Nullable String> actors() {
        final Caller caller = callerService.current();
        final Map<String, @Nullable String> actors = new LinkedHashMap<>();

        caller.requireEditor();
        for (final Object[] row : repository.findActors()) {
            final String subject = (String) row[0];

            if ((caller.isAdmin() || subject.equals(caller.subject())) && !actors.containsKey(subject)) {
                actors.put(subject, (String) row[1]);
            }
        }
        return actors;
    }
}
