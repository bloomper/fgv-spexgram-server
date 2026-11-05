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


package nu.fgv.spexgram.server.visibility;

import nu.fgv.spexgram.server.user.Caller;
import nu.fgv.spexgram.server.user.CallerService;
import org.springframework.stereotype.Service;

/**
 * Resolves the viewer of the current request and records members as users.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Service
public class ViewerService {

    private final CallerService callerService;

    public ViewerService(final CallerService callerService) {
        this.callerService = callerService;
    }

    static Viewer.Kind kindOf(final Caller caller) {
        if (caller.isAdmin()) {
            return Viewer.Kind.ADMIN;
        }
        if (caller.isEditor()) {
            return Viewer.Kind.EDITOR;
        }
        return caller.isUser() ? Viewer.Kind.USER : Viewer.Kind.VISITOR;
    }

    /**
     * The viewer of the current request; Spexipedia's service account is a visitor here.
     */
    public Viewer current() {
        return callerService.currentIfAny()
                .map(caller -> {
                    final Viewer viewer = new Viewer(kindOf(caller), caller);

                    if (viewer.isMember()) {
                        callerService.record(caller);
                    }
                    return viewer;
                })
                .orElseGet(Viewer::visitor);
    }

    /**
     * The viewer for the queries Spexipedia uses: its service account sees members' items too.
     */
    public Viewer currentForSpexipedia() {
        return callerService.currentIfAny()
                .filter(caller -> !caller.isUser() && caller.isSpexipediaService())
                .map(caller -> new Viewer(Viewer.Kind.SPEXIPEDIA, caller))
                .orElseGet(this::current);
    }
}
