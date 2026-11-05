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


package nu.fgv.spexgram.server.media;

import jakarta.servlet.http.HttpServletResponse;
import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.album.AlbumRepository;
import nu.fgv.spexgram.server.immich.ImmichClient;
import nu.fgv.spexgram.server.item.Item;
import nu.fgv.spexgram.server.item.ItemRepository;
import nu.fgv.spexgram.server.item.ItemSpecifications;
import nu.fgv.spexgram.server.item.ItemViews;
import nu.fgv.spexgram.server.settings.SettingsService;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.ViewerService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * An album as a zip of its originals, made by Immich's download endpoint and streamed through the API, which checks
 * access first: members who may download originals (5.1 of the spec), only items they may see.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@RestController
public class DownloadController {

    private final AlbumRepository albumRepository;
    private final ItemRepository itemRepository;
    private final ViewerService viewerService;
    private final SettingsService settingsService;
    private final ImmichClient immichClient;
    private final TransactionTemplate transactionTemplate;

    public DownloadController(final AlbumRepository albumRepository, final ItemRepository itemRepository,
                              final ViewerService viewerService, final SettingsService settingsService,
                              final ImmichClient immichClient, final TransactionTemplate transactionTemplate) {
        this.albumRepository = albumRepository;
        this.itemRepository = itemRepository;
        this.viewerService = viewerService;
        this.settingsService = settingsService;
        this.immichClient = immichClient;
        this.transactionTemplate = transactionTemplate;
    }

    @GetMapping("/api/albums/{id}/zip")
    public void zip(@PathVariable final UUID id, final HttpServletResponse response) throws IOException {
        final Viewer viewer = viewerService.current();

        viewer.requireMember();
        if (!ItemViews.canDownloadOriginals(viewer, settingsService.current())) {
            throw new AccessDeniedException("May not download originals");
        }

        final Download download = Objects.requireNonNull(transactionTemplate.execute(_ -> {
            final Album album = albumRepository.findByUuid(id).filter(found -> ItemViews.canSee(found, viewer))
                    .orElseThrow(() -> new ResourceNotFoundException(Album.class, id));
            final List<UUID> assets = itemRepository.findAll(ItemSpecifications.visibleTo(viewer)
                            .and((root, query, builder) -> builder.equal(root.get("album"), album))).stream()
                    .map(Item::getImmichAssetId).filter(Objects::nonNull).toList();

            return new Download(album.getTitle(), assets);
        }));

        if (download.assets().isEmpty()) {
            throw new ResourceNotFoundException("error.album.empty", id);
        }
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(download.title() + ".zip", StandardCharsets.UTF_8).build().toString());
        immichClient.downloadArchive(download.assets(), response.getOutputStream());
    }

    record Download(String title, List<UUID> assets) {
    }
}
