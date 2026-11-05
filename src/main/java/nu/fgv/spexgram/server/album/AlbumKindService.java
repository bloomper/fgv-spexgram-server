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


import nu.fgv.spexgram.server.audit.AuditService;
import nu.fgv.spexgram.server.user.Caller;
import nu.fgv.spexgram.server.user.CallerService;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.util.error.ConflictException;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The album kinds: everyone reads them, admins add, rename, reorder and delete them. A kind that albums use, trashed
 * ones included, cannot be deleted, and neither can the last one.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class AlbumKindService {

    static final int MAX_NAME = 100;

    private final AlbumKindRepository repository;
    private final CallerService callerService;
    private final AuditService auditService;

    public AlbumKindService(final AlbumKindRepository repository, final CallerService callerService,
                            final AuditService auditService) {
        this.repository = repository;
        this.callerService = callerService;
        this.auditService = auditService;
    }

    static AlbumKindDto toDto(final AlbumKind kind) {
        return new AlbumKindDto(kind.getUuid(), kind.getNameSv(), kind.getNameEn(), kind.getPosition());
    }

    private static String name(final @Nullable String value) {
        if (!StringUtils.hasText(value) || value.strip().length() > MAX_NAME) {
            throw new BadRequestException("error.albumKind.invalid");
        }
        return value.strip();
    }

    @Transactional(readOnly = true)
    public List<AlbumKindDto> kinds() {
        return repository.findAllByOrderByPositionAscIdAsc().stream().map(AlbumKindService::toDto).toList();
    }

    /**
     * Whether each kind is the default, the first by position.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Boolean> defaults(final Collection<UUID> ids) {
        final UUID first = repository.findFirstByOrderByPositionAscIdAsc().map(AlbumKind::getUuid).orElse(null);

        return ids.stream().distinct().collect(Collectors.toMap(Function.identity(), id -> id.equals(first)));
    }

    /**
     * How many albums, trashed ones included, have each kind.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> albumCounts(final Collection<UUID> ids) {
        final Map<Long, UUID> uuids = repository.findAll().stream().collect(Collectors.toMap(AlbumKind::getId, AlbumKind::getUuid));
        final Map<UUID, Integer> counts = new HashMap<>();

        for (final Object[] row : repository.countAlbumsByKind()) {
            counts.put(uuids.get((Long) row[0]), ((Number) row[1]).intValue());
        }
        return ids.stream().distinct().collect(Collectors.toMap(Function.identity(), id -> counts.getOrDefault(id, 0)));
    }

    /**
     * The kind for an album: the given one, or the default when none is given.
     */
    @Transactional(readOnly = true)
    public AlbumKind kindOrDefault(final @Nullable UUID id) {
        if (id == null) {
            return repository.findFirstByOrderByPositionAscIdAsc().orElseThrow(() -> new ResourceNotFoundException("error.albumKind.notFound", ""));
        }
        return kind(id);
    }

    @Transactional
    public AlbumKindDto create(final AlbumKindInput input) {
        callerService.current().requireAdmin();

        final AlbumKind kind = new AlbumKind();

        kind.setNameSv(name(input.nameSv()));
        kind.setNameEn(name(input.nameEn()));
        kind.setPosition(repository.findAllByOrderByPositionAscIdAsc().stream().mapToInt(AlbumKind::getPosition).max().orElse(0) + 1);
        repository.save(kind);
        auditService.record("CREATE", "ALBUM_KIND", kind.getUuid(), "Skapade albumsorten " + kind.getNameSv());
        return toDto(kind);
    }

    @Transactional
    public AlbumKindDto update(final UUID id, final AlbumKindInput input) {
        callerService.current().requireAdmin();

        final AlbumKind kind = kind(id);

        kind.setNameSv(name(input.nameSv()));
        kind.setNameEn(name(input.nameEn()));
        auditService.record("UPDATE", "ALBUM_KIND", kind.getUuid(), "Ändrade albumsorten " + kind.getNameSv());
        return toDto(kind);
    }

    @Transactional
    public List<AlbumKindDto> reorder(final List<UUID> ids) {
        final Caller caller = callerService.current();

        caller.requireAdmin();

        final List<AlbumKind> kinds = repository.findAll();
        final Map<UUID, AlbumKind> byId = kinds.stream().collect(Collectors.toMap(AlbumKind::getUuid, Function.identity()));

        if (ids.size() != kinds.size() || !byId.keySet().equals(new HashSet<>(ids))) {
            throw new BadRequestException("error.albumKind.reorder");
        }
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).setPosition(i + 1);
        }
        auditService.record("UPDATE", "ALBUM_KIND", null, "Ändrade ordningen på albumsorterna");
        return ids.stream().map(byId::get).map(AlbumKindService::toDto).toList();
    }

    @Transactional
    public void delete(final UUID id) {
        callerService.current().requireAdmin();

        final AlbumKind kind = kind(id);
        final long albums = repository.countAlbums(kind);

        if (albums > 0) {
            throw new ConflictException("error.albumKind.inUse", albums);
        }
        if (repository.count() <= 1) {
            throw new BadRequestException("error.albumKind.last");
        }
        repository.delete(kind);
        auditService.record("DELETE", "ALBUM_KIND", kind.getUuid(), "Raderade albumsorten " + kind.getNameSv());
    }

    private AlbumKind kind(final UUID id) {
        return repository.findByUuid(id).orElseThrow(() -> new ResourceNotFoundException("error.albumKind.notFound", id));
    }

    public record AlbumKindDto(UUID id, String nameSv, String nameEn, int position) {
    }

    public record AlbumKindInput(@Nullable String nameSv, @Nullable String nameEn) {
    }
}
