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

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import nu.fgv.spexgram.server.audit.AuditService;
import nu.fgv.spexgram.server.item.Item;
import nu.fgv.spexgram.server.item.ItemRepository;
import nu.fgv.spexgram.server.item.ItemViews;
import nu.fgv.spexgram.server.mail.Mailer;
import nu.fgv.spexgram.server.user.Caller;
import nu.fgv.spexgram.server.user.UserDirectory;
import nu.fgv.spexgram.server.util.Locales;
import nu.fgv.spexgram.server.util.OpenFilter;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reports (5.5 of the spec). Anyone who may see an item may report it; visitors give an email address and pass
 * Cloudflare Turnstile. A report that the reporter is in the item and wants it removed hides it at once until an editor
 * has handled the report: a visitor's report makes a public item a members' item (a visitor cannot hide anything from
 * members), a member's report makes it unpublished. Editors are emailed of new reports; the reporter is emailed the
 * outcome. Reports are deleted 24 months after they are handled.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Service
public class ReportService {

    static final int RETENTION_MONTHS = 24;
    private static final int MAX_MESSAGE = 2000;
    private static final int MAX_EMAIL = 255;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final ReportRepository repository;
    private final ItemRepository itemRepository;
    private final ViewerService viewerService;
    private final TurnstileVerifier turnstile;
    private final AuditService auditService;
    private final UserDirectory directory;
    private final Mailer mailer;
    private final TransactionTemplate transactionTemplate;
    private final VisitorReportLimiter limiter;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public ReportService(final ReportRepository repository, final ItemRepository itemRepository,
                         final ViewerService viewerService, final TurnstileVerifier turnstile,
                         final AuditService auditService, final UserDirectory directory, final Mailer mailer,
                         final TransactionTemplate transactionTemplate, final VisitorReportLimiter limiter,
                         final Clock clock) {
        this.limiter = limiter;
        this.repository = repository;
        this.itemRepository = itemRepository;
        this.viewerService = viewerService;
        this.turnstile = turnstile;
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

    static String caption(final Item item) {
        final String caption = StringUtils.hasText(item.getCaption()) ? item.getCaption() : item.getUuid().toString();

        return caption.length() > 255 ? caption.substring(0, 255) : caption;
    }

    /**
     * Hides a reported item from the reporter's kind: a visitor's report narrows a public item to members, a member's
     * report unpublishes it.
     *
     * @return the visibility it had, or null when it was already hidden from them
     */
    static @Nullable Visibility hide(final Item item, final boolean visitor) {
        final Visibility before = item.getVisibility();
        final Visibility after = visitor ? Visibility.MEMBERS : Visibility.UNPUBLISHED;

        if (before.compareTo(after) <= 0) {
            return null;
        }
        item.setVisibility(after);
        return before;
    }

    private ReportDto toDto(final Report report, final boolean editor) {
        return new ReportDto(report.getUuid(), report.getItem() == null ? null : report.getItem().getUuid(), report.getReason(),
                report.getMessage(), report.getStatus(),
                editor ? (report.getVisitorEmail() != null ? report.getVisitorEmail() : report.getReporterName()) : null,
                report.getVisitorEmail() != null, report.getOutcome(), report.getOutcomeMessage(), report.getCreatedAt(),
                report.getHandledAt());
    }

    /**
     * Reports an item the viewer may see.
     */
    public ReportDto create(final ReportInput input, final @Nullable Locale locale, final @Nullable String clientIp) {
        final Viewer viewer = viewerService.current();
        final boolean visitor = !viewer.isMember();
        final String email = input.email() == null ? null : input.email().strip();

        if (visitor) {
            if (email == null || email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
                throw new BadRequestException("error.report.email");
            }
            // Turnstile first: only a passed check counts against the limits, so that nobody can use up a visitor's (or,
            // without the web's address, every visitor's) reports for the hour without passing it
            if (input.turnstileToken() == null || !turnstile.verify(input.turnstileToken())) {
                throw new BadRequestException("error.report.turnstile");
            }
            if (!limiter.tryAcquire(clientIp, email)) {
                throw new BadRequestException("error.report.tooMany");
            }
        }

        final ReportDto created = Objects.requireNonNull(transactionTemplate.execute(_ -> {
            final Item item = itemRepository.findByUuid(input.itemId())
                    .filter(found -> found.getAlbum() != null && ItemViews.canSee(found, viewer))
                    .orElseThrow(() -> new ResourceNotFoundException(Item.class, input.itemId()));
            final Report report = new Report();
            final Caller caller = visitor ? null : viewer.caller();

            report.setItem(item);
            report.setItemCaption(caption(item));
            report.setReporterSubject(caller == null ? null : caller.subject());
            report.setReporterName(caller == null ? null : caller.displayName());
            report.setVisitorEmail(visitor ? email : null);
            report.setLocale(Locales.supported(locale).getLanguage());
            report.setReason(input.reason());
            report.setMessage(message(input.message()));
            report.setCreatedAt(clock.instant());
            if (input.reason() == Report.Reason.I_AM_IN_IT) {
                report.setHiddenFrom(hide(item, visitor));
            }
            repository.save(report);
            auditService.record("REPORT", "ITEM", item.getUuid(), "%s anmälde %s%s".formatted(visitor ? "En besökare" : "En medlem",
                    caption(item), report.getHiddenFrom() != null ? ", som doldes" : ""));
            return toDto(report, false);
        }));

        tellEditors(created);
        return created;
    }

    private void tellEditors(final ReportDto report) {
        try {
            for (final UserDirectory.Recipient editor : directory.editors()) {
                mailer.send(editor.email(), mailer.text("mail.report.subject", editor.locale()), List.of(
                        mailer.text("mail.report.created", editor.locale(), mailer.text("report.reason." + report.reason().name(), editor.locale())),
                        report.message() == null ? "" : "\n" + report.message(),
                        "",
                        mailer.link("/manage/reports")));
            }
        } catch (final DirectoryUnavailableException e) {
            log.warn("Could not read the editors' addresses in Keycloak, so they are not told of report {}", report.id());
        }
    }

    /**
     * Everyone's reports for editors, unless mine; the member's own otherwise. Open ones, handled ones, or all when open
     * is null.
     */
    @Transactional(readOnly = true)
    public List<ReportDto> reports(final @Nullable Boolean open, final boolean mine) {
        final Viewer viewer = viewerService.current();
        final Caller caller = viewer.requireMember();

        if (viewer.isEditor() && !mine) {
            final List<Report> found = open == null ? repository.findAllByOrderByCreatedAtDesc()
                    : open ? repository.findByStatusOrderByCreatedAtAsc(Report.Status.OPEN)
                    : repository.findByStatusOrderByHandledAtDesc(Report.Status.HANDLED);

            return found.stream().map(report -> toDto(report, true)).toList();
        }
        return repository.findByReporterSubjectOrderByCreatedAtDesc(caller.subject()).stream()
                .filter(report -> OpenFilter.wanted(open, report.getStatus() == Report.Status.OPEN))
                .map(report -> toDto(report, false))
                .toList();
    }

    public ReportDto handle(final UUID id, final Report.Outcome outcome, final @Nullable String text) {
        final Caller caller = viewerService.current().requireEditor();
        final Report handled = Objects.requireNonNull(transactionTemplate.execute(_ -> {
            final Report report = repository.findByUuid(id)
                    .filter(found -> found.getStatus() == Report.Status.OPEN)
                    .orElseThrow(() -> new ResourceNotFoundException(Report.class, id));
            final Item item = report.getItem();

            report.setStatus(Report.Status.HANDLED);
            report.setOutcome(outcome);
            report.setOutcomeMessage(message(text));
            report.setHandledBy(caller.subject());
            report.setHandledAt(clock.instant());
            if (item != null) {
                if (outcome == Report.Outcome.REMOVED) {
                    item.setTrashedAt(clock.instant());
                } else if (report.getHiddenFrom() != null) {
                    showAgain(report, item);
                }
                auditService.record("REPORT", "ITEM", item.getUuid(), "Hanterade en anmälan av %s: %s".formatted(caption(item),
                        switch (outcome) {
                            case REMOVED -> "lade den i papperskorgen";
                            case CORRECTED -> "rättade den";
                            case KEPT -> "behöll den";
                        }));
            }
            return report;
        }));

        tellReporter(handled);
        return toDto(handled, true);
    }

    /**
     * Gives the item back the visibility the report took from it, unless other open reports still hide it.
     */
    private void showAgain(final Report report, final Item item) {
        showAgain(report, item, repository.findByItemAndStatus(item, Report.Status.OPEN).stream()
                .filter(other -> !other.getId().equals(report.getId()) && other.getHiddenFrom() != null)
                .toList());
    }

    /**
     * The visibility a report gave the item when it hid it: a visitor's makes it a members' item, a member's
     * unpublished.
     */
    static Visibility hiddenAs(final Report report) {
        return report.getVisitorEmail() != null ? Visibility.MEMBERS : Visibility.UNPUBLISHED;
    }

    /**
     * When the earliest of the other hiding reports came after this one, it hid an item this one had hidden, so it learns
     * what the item was before this one. Else this one hid last, and the item gets back what it had before, but only if
     * it still has what the report gave it: an editor who has changed it meanwhile decided otherwise.
     */
    static void showAgain(final Report report, final Item item, final List<Report> otherOpenHiding) {
        final Optional<Report> earliestOther = otherOpenHiding.stream().min(Comparator.comparing(Report::getCreatedAt));

        if (earliestOther.isPresent() && report.getCreatedAt().isBefore(earliestOther.get().getCreatedAt())) {
            earliestOther.get().setHiddenFrom(report.getHiddenFrom());
        } else if (item.getVisibility() == hiddenAs(report)) {
            item.setVisibility(Objects.requireNonNull(report.getHiddenFrom()));
        }
    }

    private void tellReporter(final Report report) {
        try {
            final Optional<String> to = report.getVisitorEmail() != null ? Optional.of(report.getVisitorEmail())
                    : Optional.ofNullable(report.getReporterSubject()).flatMap(directory::user).map(UserDirectory.Recipient::email);
            final Locale locale = Locale.forLanguageTag(report.getLocale());

            to.ifPresent(address -> {
                final List<String> lines = new ArrayList<>();

                lines.add(mailer.text("mail.report.outcome." + Objects.requireNonNull(report.getOutcome()).name(), locale,
                        Objects.requireNonNullElse(report.getItemCaption(), "")));
                if (report.getOutcomeMessage() != null) {
                    lines.add("");
                    lines.add(report.getOutcomeMessage());
                }
                mailer.send(address, mailer.text("mail.report.outcome.subject", locale), lines);
            });
        } catch (final DirectoryUnavailableException e) {
            log.warn("Could not read the reporter's address in Keycloak, so they are not told the outcome of report {}", report.getUuid());
        }
    }

    /**
     * Deletes reports 24 months after they were handled.
     */
    @SchedulerLock(name = "report-retention", lockAtMostFor = "PT10M")
    @Transactional
    public void cleanUp() {
        repository.deleteByHandledAtBefore(clock.instant().atZone(ZoneOffset.UTC).minusMonths(RETENTION_MONTHS).toInstant());
    }

    public record ReportInput(UUID itemId, Report.Reason reason, @Nullable String message, @Nullable String email,
                              @Nullable String turnstileToken) {
    }

    /**
     * @param reporter the member's name or the visitor's address, for editors
     */
    public record ReportDto(UUID id, @Nullable UUID itemId, Report.Reason reason, @Nullable String message,
                            Report.Status status, @Nullable String reporter, boolean byVisitor,
                            Report.@Nullable Outcome outcome, @Nullable String outcomeMessage, Instant createdAt,
                            @Nullable Instant handledAt) {
    }
}
