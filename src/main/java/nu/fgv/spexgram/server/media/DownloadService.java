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

import nu.fgv.spexgram.server.album.Album;
import nu.fgv.spexgram.server.album.AlbumRepository;
import nu.fgv.spexgram.server.item.Item;
import nu.fgv.spexgram.server.item.ItemRepository;
import nu.fgv.spexgram.server.item.ItemSpecifications;
import nu.fgv.spexgram.server.item.ItemViews;
import nu.fgv.spexgram.server.settings.SettingsService;
import nu.fgv.spexgram.server.util.error.ResourceNotFoundException;
import nu.fgv.spexgram.server.visibility.Viewer;
import nu.fgv.spexgram.server.visibility.ViewerService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Decides what of an album a user may download as a zip (5.1 of the spec): members who may download originals, only
 * the items they may see.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class DownloadService {

    private final AlbumRepository albumRepository;
    private final ItemRepository itemRepository;
    private final ViewerService viewerService;
    private final SettingsService settingsService;

    public DownloadService(final AlbumRepository albumRepository, final ItemRepository itemRepository,
                           final ViewerService viewerService, final SettingsService settingsService) {
        this.albumRepository = albumRepository;
        this.itemRepository = itemRepository;
        this.viewerService = viewerService;
        this.settingsService = settingsService;
    }

    @Transactional(readOnly = true)
    public Download album(final UUID id) {
        final Viewer viewer = viewerService.current();

        viewer.requireMember();
        if (!ItemViews.canDownloadOriginals(viewer, settingsService.current())) {
            throw new AccessDeniedException("May not download originals");
        }

        final Album album = albumRepository.findByUuid(id).filter(found -> ItemViews.canSee(found, viewer))
                .orElseThrow(() -> new ResourceNotFoundException(Album.class, id));
        final List<UUID> assets = itemRepository.findAll(ItemSpecifications.visibleTo(viewer)
                        .and((root, query, builder) -> builder.equal(root.get("album"), album))).stream()
                .map(Item::getImmichAssetId).filter(Objects::nonNull).toList();

        if (assets.isEmpty()) {
            throw new ResourceNotFoundException("error.album.empty", id);
        }
        return new Download(album.getTitle(), assets);
    }

    /**
     * What to put in the zip and what to call it.
     */
    public record Download(String title, List<UUID> assets) {
    }
}
