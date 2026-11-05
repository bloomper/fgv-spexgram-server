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


package nu.fgv.spexgram.server.item;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.audit.AuditService;
import nu.fgv.spexgram.server.immich.ImmichClient;
import nu.fgv.spexgram.server.immich.ImmichRejectedException;
import nu.fgv.spexgram.server.item.ItemDtos.UploadOutcome;
import nu.fgv.spexgram.server.item.ItemDtos.UploadResult;
import nu.fgv.spexgram.server.upload.UploadService;
import nu.fgv.spexgram.server.user.Caller;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.util.error.ConflictException;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends finished uploads to Immich and makes items of them (5.2, 5.4 of the spec). Immich recognises a file it already
 * has by its checksum; the uploader is then told where the existing item is instead of getting a duplicate. A file
 * Immich does not accept is rejected with Immich's reason. The upload is released either way.
 *
 * <p>Runs without a transaction around the transfer, so that no database connection is held while a large video
 * streams to Immich.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class ItemIntake {

    private final ItemRepository repository;
    private final UploadService uploadService;
    private final ImmichClient immichClient;
    private final ItemProcessing processing;
    private final ItemViews views;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public ItemIntake(final ItemRepository repository, final UploadService uploadService, final ImmichClient immichClient,
                      final ItemProcessing processing, final ItemViews views, final AuditService auditService,
                      final TransactionTemplate transactionTemplate, final Clock clock) {
        this.repository = repository;
        this.uploadService = uploadService;
        this.immichClient = immichClient;
        this.processing = processing;
        this.views = views;
        this.auditService = auditService;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * Adds the uploads to the album: as members' items for editors, as contributions waiting for review otherwise.
     */
    public List<UploadResult> add(final Album album, final List<String> uploadIds, final Viewer viewer, final boolean contribution) {
        final Caller caller = Objects.requireNonNull(viewer.caller());
        final List<UploadResult> results = new ArrayList<>();

        for (final String uploadId : uploadIds) {
            final UploadService.Claimed upload = uploadService.claim(uploadId, caller);

            try {
                results.add(add(album, upload, viewer, contribution));
            } finally {
                uploadService.release(uploadId);
            }
        }
        return results;
    }

    private UploadResult add(final Album album, final UploadService.Claimed upload, final Viewer viewer, final boolean contribution) {
        final Instant modified = upload.lastModified() != null ? upload.lastModified() : clock.instant();
        final ImmichClient.Upload sent;

        try {
            sent = immichClient.upload(upload.file(), upload.fileName(), modified, modified);
        } catch (final ImmichRejectedException e) {
            log.info("Immich rejected an upload: {}", e.getMessage());
            return new UploadResult(upload.id(), upload.fileName(), UploadOutcome.REJECTED, null, null, null, e.getMessage());
        }

        final UploadResult result = Objects.requireNonNull(transactionTemplate.execute(_ -> {
            final Optional<Item> existing = repository.findByImmichAssetId(sent.id());

            if (existing.isPresent() && !(viewer.isEditor() && existing.get().getAlbum() == null)) {
                final Item item = existing.get();
                final boolean visible = ItemViews.canSee(item, viewer);
                // An album the caller may not see is not named: the answer is only that the file is already here
                final boolean albumVisible = item.getAlbum() != null && ItemViews.canSee(item.getAlbum(), viewer);

                return new UploadResult(upload.id(), upload.fileName(), UploadOutcome.DUPLICATE, null,
                        visible ? views.toDto(item, viewer) : null,
                        albumVisible ? item.getAlbum().getTitle() : null, null);
            }

            // An unsorted item an editor uploads again is sorted into the album; else a new item
            final Item item = existing.orElseGet(Item::new);

            item.setAlbum(album);
            item.setImmichAssetId(sent.id());
            item.setTranscoded(false);
            item.setOriginalFileName(upload.fileName());
            item.setFileSize(upload.length());
            item.setStatus(ItemStatus.PROCESSING);
            item.setProcessingSince(clock.instant());
            item.setUploadedBySubject(viewer.subject());
            item.setUploadedByName(viewer.caller() == null ? null : viewer.caller().displayName());
            if (contribution) {
                item.setVisibility(Visibility.UNPUBLISHED);
                item.setContributionStatus(ContributionStatus.WAITING);
                item.setPhotographer(item.getUploadedByName());
            } else {
                item.setVisibility(Visibility.MEMBERS);
            }
            repository.save(item);
            auditService.record("UPLOAD", "ITEM", item.getUuid(), "%s %s till albumet %s".formatted(
                    contribution ? "Bidrog med" : "Laddade upp", upload.fileName(), album.getTitle()));
            return new UploadResult(upload.id(), upload.fileName(), UploadOutcome.CREATED, null, null, null, null);
        }));

        if (result.outcome() != UploadOutcome.CREATED) {
            return result;
        }

        final Item item = repository.findByImmichAssetId(sent.id()).orElseThrow();

        // A file Immich already had may be processed by now
        processing.refresh(item.getId());
        return new UploadResult(result.uploadId(), result.fileName(), UploadOutcome.CREATED,
                transactionTemplate.execute(_ -> views.toDto(repository.findById(item.getId()).orElseThrow(), viewer)),
                null, null, null);
    }

    /**
     * Replaces an item's file with an upload, e.g. a cropped photo (5.3); the old file is deleted from Immich and the
     * item is processed again.
     */
    public ItemDtos.ItemDto replaceFile(final UUID itemId, final String uploadId, final Viewer viewer) {
        final Caller caller = viewer.requireEditor();
        final Item current = repository.findByUuid(itemId).orElseThrow(() -> new ResourceNotFoundException(Item.class, itemId));
        final UploadService.Claimed upload = uploadService.claim(uploadId, caller);
        final ImmichClient.Upload sent;

        try {
            final Instant modified = upload.lastModified() != null ? upload.lastModified() : clock.instant();

            sent = immichClient.upload(upload.file(), upload.fileName(), modified, modified);
        } catch (final ImmichRejectedException e) {
            throw new BadRequestException("error.item.rejected", e.getMessage());
        } finally {
            uploadService.release(uploadId);
        }
        if (repository.findByImmichAssetId(sent.id()).filter(other -> !other.getId().equals(current.getId())).isPresent()) {
            throw new ConflictException("error.item.duplicate");
        }

        final @Nullable UUID old = current.getImmichAssetId();

        transactionTemplate.executeWithoutResult(_ -> {
            final Item item = repository.findById(current.getId()).orElseThrow();

            item.setImmichAssetId(sent.id());
            item.setTranscoded(false);
            item.setOriginalFileName(upload.fileName());
            item.setFileSize(upload.length());
            item.setStatus(ItemStatus.PROCESSING);
            item.setFailure(null);
            item.setProcessingSince(clock.instant());
            auditService.record("UPLOAD", "ITEM", item.getUuid(), "Ersatte filen för %s med %s".formatted(ItemService.describe(item), upload.fileName()));
        });
        if (old != null && !old.equals(sent.id())) {
            immichClient.delete(List.of(old));
        }
        processing.refresh(current.getId());
        return Objects.requireNonNull(transactionTemplate.execute(_ -> views.toDto(repository.findById(current.getId()).orElseThrow(), viewer)));
    }
}
