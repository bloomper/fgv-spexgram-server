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

/**
 * Where Immich is with an item's file.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
public enum ItemStatus {
    /**
     * Immich is reading its metadata, making thumbnails or transcoding.
     */
    PROCESSING,
    READY,
    /**
     * Immich could not process it, or it was rejected, e.g. a video longer than allowed; {@code failure} says why.
     */
    FAILED
}
