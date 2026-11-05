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


package nu.fgv.spexgram.server.contribution;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.album.AlbumDtos.AlbumInput;
import nu.fgv.spexgram.server.album.AlbumKindService;
import nu.fgv.spexgram.server.album.AlbumRepository;
import nu.fgv.spexgram.server.album.AlbumService;
import nu.fgv.spexgram.server.audit.AuditService;
import nu.fgv.spexgram.server.item.ContributionStatus;
import nu.fgv.spexgram.server.item.Item;
import nu.fgv.spexgram.server.item.ItemDtos.ItemDto;
import nu.fgv.spexgram.server.item.ItemDtos.UploadResult;
import nu.fgv.spexgram.server.item.ItemIntake;
import nu.fgv.spexgram.server.item.ItemRepository;
import nu.fgv.spexgram.server.item.ItemService;
import nu.fgv.spexgram.server.item.ItemViews;
import nu.fgv.spexgram.server.mail.Mailer;
import nu.fgv.spexgram.server.user.Caller;
import nu.fgv.spexgram.server.user.UserDirectory;
import nu.fgv.spexgram.server.util.error.BadRequestException;
import nu.fgv.spexgram.server.util.error.DirectoryUnavailableException;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.ViewerService;
import nu.fgv.spexgram.server.visibility.Visibility;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Members' contributions and album suggestions, and editors' reviews of them (5.4 of the spec). Contributions start
 * unpublished and wait for review; accepting makes them members' items, rejecting deletes their files at once.
 * Contributors are told of reviews by email at most once a day, suggesters at once.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class ContributionService {

    static final int RETENTION_MONTHS = 24;
    private static final int MAX_MESSAGE = 2000;

    private final ItemRepository itemRepository;
    private final AlbumRepository albumRepository;
    private final ContributionReviewRepository reviewRepository;
    private final AlbumSuggestionRepository suggestionRepository;
    private final ContributionQuota quota;
    private final ItemIntake intake;
    private final ItemService itemService;
    private final ItemViews views;
    private final AlbumService albumService;
    private final AlbumKindService kindService;
    private final ViewerService viewerService;
    private final AuditService auditService;
    private final UserDirectory directory;
    private final Mailer mailer;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public ContributionService(final ItemRepository itemRepository, final AlbumRepository albumRepository,
                               final ContributionReviewRepository reviewRepository,
                               final AlbumSuggestionRepository suggestionRepository, final ContributionQuota quota,
                               final ItemIntake intake, final ItemService itemService, final ItemViews views,
                               final AlbumService albumService, final AlbumKindService kindService,
                               final ViewerService viewerService, final AuditService auditService,
                               final UserDirectory directory, final Mailer mailer,
                               final TransactionTemplate transactionTemplate, final Clock clock) {
        this.itemRepository = itemRepository;
        this.albumRepository = albumRepository;
        this.reviewRepository = reviewRepository;
        this.suggestionRepository = suggestionRepository;
        this.quota = quota;
        this.intake = intake;
        this.itemService = itemService;
        this.views = views;
        this.albumService = albumService;
        this.kindService = kindService;
        this.viewerService = viewerService;
        this.auditService = auditService;
        this.directory = directory;
        this.mailer = mailer;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    private static @Nullable String message(final @Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.strip().length() > MAX_MESSAGE) {
            throw new BadRequestException("error.message.tooLong", MAX_MESSAGE);
        }
        return value.strip();
    }

    private static AlbumSuggestionDto toDto(final AlbumSuggestion suggestion, final boolean editor) {
        return new AlbumSuggestionDto(suggestion.getUuid(), suggestion.getTitle(),
                suggestion.getAlbum() == null ? null : suggestion.getAlbum().getUuid(),
                editor ? suggestion.getSuggestedByName() : null, suggestion.getStatus(), suggestion.getMessage(),
                suggestion.getCreatedAt(), suggestion.getHandledAt());
    }

    // ---------------------------------------------------------------------------------------------- contributing

    /**
     * Sends uploads as contributions to an album for members or the public, or one the member suggested.
     */
    public List<UploadResult> contribute(final UUID albumId, final List<String> uploadIds) {
        final Viewer viewer = viewerService.current();
        final Caller caller = viewer.requireMember();
        final Album album = Objects.requireNonNull(transactionTemplate.execute(_ -> albumRepository.findByUuid(albumId)
                .filter(found -> !found.isTrashed() && ItemViews.canSee(found, viewer)
                        && (found.getVisibility() != Visibility.UNPUBLISHED || caller.subject().equals(found.getSuggestedBy())))
                .orElseThrow(() -> new ResourceNotFoundException(Album.class, albumId))));

        quota.requireItems(caller.subject(), uploadIds.size());
        return intake.add(album, uploadIds, viewer, true);
    }

    /**
     * Takes back one of the member's contributions that still waits; its file is deleted.
     */
    @Transactional
    public void withdraw(final UUID itemId) {
        final Caller caller = viewerService.current().requireMember();
        final Item item = itemRepository.findByUuid(itemId)
                .filter(found -> found.isWaitingForReview() && caller.subject().equals(found.getUploadedBySubject()))
                .orElseThrow(() -> new ResourceNotFoundException(Item.class, itemId));

        itemService.deleteWithFiles(List.of(item));
        auditService.record("DELETE", "ITEM", item.getUuid(), "Tog tillbaka bidraget " + Objects.requireNonNullElse(item.getOriginalFileName(), ""));
    }

    @Transactional(readOnly = true)
    public List<ItemDto> mine() {
        final Viewer viewer = viewerService.current();
        final Caller caller = viewer.requireMember();
        final ItemViews.Context context = views.context(viewer);

        return itemRepository.findByUploadedBySubjectAndContributionStatusIsNotNullOrderByCreatedAtDesc(caller.subject()).stream()
                .filter(item -> !item.isTrashed())
                .map(item -> views.toDto(item, context))
                .toList();
    }

    /**
     * Contributions waiting for review, of one album or all, oldest first.
     */
    @Transactional(readOnly = true)
    public List<ItemDto> waiting(final @Nullable UUID albumId) {
        final Viewer viewer = viewerService.current();

        viewer.requireEditor();

        final ItemViews.Context context = views.context(viewer);

        return itemRepository.findByContributionStatusOrderByCreatedAtAsc(ContributionStatus.WAITING).stream()
                .filter(item -> !item.isTrashed())
                .filter(item -> albumId == null || (item.getAlbum() != null && albumId.equals(item.getAlbum().getUuid())))
                .map(item -> views.toDto(item, context))
                .toList();
    }

    @Transactional
    public List<ContributionReviewDto> review(final List<UUID> itemIds, final boolean accept, final @Nullable String text) {
        final Caller caller = viewerService.current().requireEditor();
        final String message = message(text);
        final List<Item> items = itemRepository.findByUuidIn(itemIds).stream().filter(Item::isWaitingForReview).toList();
        final List<ContributionReviewDto> result = new ArrayList<>();

        if (items.size() != itemIds.stream().distinct().count()) {
            throw new BadRequestException("error.contribution.notWaiting");
        }
        for (final Item item : items) {
            final ContributionReview review = new ContributionReview();

            review.setItemId(accept ? item.getId() : null);
            review.setAlbumTitle(item.getAlbum() == null ? null : item.getAlbum().getTitle());
            review.setFileName(item.getOriginalFileName());
            review.setContributorSubject(Objects.requireNonNull(item.getUploadedBySubject()));
            review.setOutcome(accept ? ContributionReview.Outcome.ACCEPTED : ContributionReview.Outcome.REJECTED);
            review.setMessage(message);
            review.setReviewerSubject(caller.subject());
            review.setReviewedAt(clock.instant());
            reviewRepository.save(review);
            if (accept) {
                item.setContributionStatus(ContributionStatus.ACCEPTED);
                item.setVisibility(Visibility.MEMBERS);
            }
            auditService.record("REVIEW", "ITEM", item.getUuid(), "%s bidraget %s".formatted(accept ? "Godkände" : "Avvisade",
                    Objects.requireNonNullElse(item.getOriginalFileName(), "")));
            result.add(new ContributionReviewDto(review.getUuid(), accept ? item.getUuid() : null, accept, message, review.getReviewedAt()));
        }
        if (!accept) {
            itemService.deleteWithFiles(items);
        }
        return result;
    }

    // ---------------------------------------------------------------------------------------------- suggestions

    @Transactional
    public AlbumSuggestionDto suggest(final AlbumSuggestionInput input) {
        final Caller caller = viewerService.current().requireMember();

        if (!StringUtils.hasText(input.title())) {
            throw new BadRequestException("error.album.titleRequired");
        }

        final Album album = new Album();
        final AlbumSuggestion suggestion = new AlbumSuggestion();

        albumService.apply(album, new AlbumInput(List.of(), input.title(), null, input.dateFrom(), input.dateTo(),
                input.place(), input.description()), kindService.kindOrDefault(input.kindId()));
        album.setSuggestedBy(caller.subject());
        albumRepository.save(album);
        suggestion.setAlbum(album);
        suggestion.setTitle(album.getTitle());
        suggestion.setSuggestedBySubject(caller.subject());
        suggestion.setSuggestedByName(caller.displayName());
        suggestion.setCreatedAt(clock.instant());
        suggestionRepository.save(suggestion);
        auditService.record("CREATE", "ALBUM", album.getUuid(), "Föreslog albumet " + album.getTitle());
        return toDto(suggestion, false);
    }

    /**
     * Open suggestions for editors; the member's own, open or handled, otherwise.
     */
    @Transactional(readOnly = true)
    public List<AlbumSuggestionDto> suggestions(final boolean open) {
        final Viewer viewer = viewerService.current();
        final Caller caller = viewer.requireMember();

        if (viewer.isEditor()) {
            return (open ? suggestionRepository.findByStatusOrderByCreatedAtAsc(AlbumSuggestion.Status.OPEN)
                    : suggestionRepository.findByStatusNotOrderByCreatedAtDesc(AlbumSuggestion.Status.OPEN))
                    .stream().map(suggestion -> toDto(suggestion, true)).toList();
        }
        return suggestionRepository.findBySuggestedBySubjectOrderByCreatedAtDesc(caller.subject()).stream()
                .filter(suggestion -> open == (suggestion.getStatus() == AlbumSuggestion.Status.OPEN))
                .map(suggestion -> toDto(suggestion, false))
                .toList();
    }

    @Transactional
    public AlbumSuggestionDto reviewSuggestion(final UUID id, final boolean accept, final @Nullable String text) {
        final Caller caller = viewerService.current().requireEditor();
        final AlbumSuggestion suggestion = suggestionRepository.findByUuid(id)
                .filter(found -> found.getStatus() == AlbumSuggestion.Status.OPEN)
                .orElseThrow(() -> new ResourceNotFoundException(AlbumSuggestion.class, id));
        final Album album = suggestion.getAlbum();

        suggestion.setStatus(accept ? AlbumSuggestion.Status.ACCEPTED : AlbumSuggestion.Status.REJECTED);
        suggestion.setMessage(message(text));
        suggestion.setHandledBy(caller.subject());
        suggestion.setHandledAt(clock.instant());
        if (album != null) {
            if (accept) {
                album.setSuggestedBy(null);
                album.setVisibility(Visibility.MEMBERS);
                auditService.record("REVIEW", "ALBUM", album.getUuid(), "Godkände förslaget på albumet " + album.getTitle());
            } else {
                suggestion.setAlbum(null);
                albumService.deleteWithItems(album, false);
                auditService.record("REVIEW", "ALBUM", album.getUuid(), "Avvisade förslaget på albumet " + album.getTitle());
            }
        }
        tellSuggester(suggestion);
        return toDto(suggestion, true);
    }

    private void tellSuggester(final AlbumSuggestion suggestion) {
        try {
            directory.user(suggestion.getSuggestedBySubject()).ifPresent(recipient -> {
                final List<String> lines = new ArrayList<>();
                final boolean accepted = suggestion.getStatus() == AlbumSuggestion.Status.ACCEPTED;

                lines.add(mailer.text(accepted ? "mail.suggestion.accepted" : "mail.suggestion.rejected", recipient.locale(), suggestion.getTitle()));
                if (suggestion.getMessage() != null) {
                    lines.add("");
                    lines.add(suggestion.getMessage());
                }
                if (accepted && suggestion.getAlbum() != null) {
                    lines.add("");
                    lines.add(mailer.link("/albums/" + suggestion.getAlbum().getUuid()));
                }
                mailer.send(recipient.email(), mailer.text("mail.suggestion.subject", recipient.locale(), suggestion.getTitle()), lines);
            });
        } catch (final DirectoryUnavailableException e) {
            log.warn("Could not read the suggester's address in Keycloak, so they are not told of the review of suggestion {}", suggestion.getUuid());
        }
    }

    // ---------------------------------------------------------------------------------------------- jobs

    /**
     * Tells each contributor, in one email, what editors decided on their contributions since the last email.
     */
    @SchedulerLock(name = "contribution-mail", lockAtMostFor = "PT30M")
    public void sendReviewMails() {
        final Map<String, List<ContributionReview>> byContributor = new LinkedHashMap<>();

        reviewRepository.findByNotifiedAtIsNullOrderByReviewedAtAsc()
                .forEach(review -> byContributor.computeIfAbsent(review.getContributorSubject(), _ -> new ArrayList<>()).add(review));
        byContributor.forEach((subject, reviews) -> {
            try {
                directory.user(subject).ifPresent(recipient -> {
                    final List<String> lines = new ArrayList<>();
                    final long accepted = reviews.stream().filter(review -> review.getOutcome() == ContributionReview.Outcome.ACCEPTED).count();

                    lines.add(mailer.text("mail.contribution.summary", recipient.locale(), accepted, reviews.size() - accepted));
                    lines.add("");
                    for (final ContributionReview review : reviews) {
                        lines.add("%s, %s: %s%s".formatted(Objects.requireNonNullElse(review.getAlbumTitle(), "–"),
                                Objects.requireNonNullElse(review.getFileName(), "–"),
                                mailer.text("mail.contribution." + review.getOutcome().name().toLowerCase(java.util.Locale.ROOT), recipient.locale()),
                                review.getMessage() == null ? "" : " – " + review.getMessage()));
                    }
                    lines.add("");
                    lines.add(mailer.link("/contributions"));
                    mailer.send(recipient.email(), mailer.text("mail.contribution.subject", recipient.locale()), lines);
                });
            } catch (final DirectoryUnavailableException e) {
                log.warn("Could not read a contributor's address in Keycloak; trying again at the next run");
                return;
            }
            transactionTemplate.executeWithoutResult(_ -> {
                final Instant now = clock.instant();

                reviewRepository.findAllById(reviews.stream().map(ContributionReview::getId).toList())
                        .forEach(review -> review.setNotifiedAt(now));
            });
        });
    }

    /**
     * Deletes reviews and handled suggestions 24 months after they were handled.
     */
    @SchedulerLock(name = "contribution-retention", lockAtMostFor = "PT10M")
    @Transactional
    public void cleanUp() {
        final Instant before = clock.instant().atZone(ZoneOffset.UTC).minusMonths(RETENTION_MONTHS).toInstant();

        reviewRepository.deleteByReviewedAtBefore(before);
        suggestionRepository.deleteByHandledAtBefore(before);
    }

    public record ContributionReviewDto(UUID id, @Nullable UUID itemId, boolean accepted, @Nullable String message,
                                        Instant reviewedAt) {
    }

    public record AlbumSuggestionInput(String title, @Nullable String dateFrom, @Nullable String dateTo,
                                       @Nullable String place, @Nullable String description, @Nullable UUID kindId) {
    }

    /**
     * @param albumId     the album, while it exists
     * @param suggestedBy the suggester's name, for editors
     */
    public record AlbumSuggestionDto(UUID id, String title, @Nullable UUID albumId, @Nullable String suggestedBy,
                                     AlbumSuggestion.Status status, @Nullable String message, Instant createdAt,
                                     @Nullable Instant handledAt) {
    }
}
