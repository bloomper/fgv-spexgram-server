# Spexgram Server

## Overview

Spexgram Server is the backend of Spexgram, FGV's archive of photos and short videos from the spex, organised in
albums. It is a Spring Boot application with a GraphQL API, which answers without login too, with what editors have
made public, and plain HTTP endpoints for resumable (tus) uploads and albums as zips. The files are stored and processed
by [Immich][11], which runs headless behind the API: the API sets it up, sends it uploads, follows its processing and
decides who may see what. The media server (`spexgram-media`, nginx) serves Immich's thumbnails, previews, originals
and videos from signed, expiring addresses. It is backed by MySQL and Keycloak ([fgv-keycloak][1]) for authentication,
and reads spex from Spexregister. [Spexgram Web][2] is its frontend and [fgv-spexgram-deploy][3] runs it on a server.

The product spec is in [fgv-spexgram-spec][4].

## Learning more

To learn more about Spexgram Server, please consult the reference documentation, which is served at `/docs`. When
running locally, the reference documentation is available [here][10]. Its source is in `src/main/asciidoc`, and
`getting-started.adoc` describes how to run the whole stack locally with `docker compose`, the sample data and the
test credentials. The GraphQL schema is `src/main/resources/graphql/schema.graphqls`.

## Immich

Immich is pinned to **v3.3.6**. Its client (`immich/ImmichClient`) is written by hand for the twenty-odd endpoints
Spexgram uses rather than generated: the OpenAPI document has several hundred operations, and Spexgram reads Immich's
large system configuration as a tree it changes a few values in. The pinned version's document is checked in as
`src/main/resources/immich/immich-openapi-specs.json`, and `ImmichClientContractTest` checks that every endpoint,
permission and configuration field the API relies on is in it. To upgrade, replace the document with the new release's
`open-api/immich-openapi-specs.json`, change `IMMICH_VERSION` in `AbstractIntegrationTest`, `docker-compose.yaml` and
fgv-spexgram-deploy, and run the tests.

Immich creates an API key's secret itself, so the API creates its three keys at start (its own, the media server's
read-only one and an upload-only one for bulk imports) and keeps them as files: the API's and the bulk import's in `API_IMMICH_KEYS_DIR`, the media server's alone in
`API_IMMICH_MEDIA_KEY_DIR`, the only folder the media server mounts (read-only), so it never sees the API's full key.

Known: Immich's previews (what visitors download) carry no position or other EXIF; the checked preview of a JPEG with
GPS had none.

## Building from source

You will need Java 25 or later to build Spexgram Server, ffmpeg for the sample data's videos, and Docker for the
integration tests. It is built using [Gradle][5]:

```
./gradlew test              # unit tests and the HTTP API documentation's snippets
./gradlew integrationTest   # integration tests, against MySQL, Keycloak and Immich in Testcontainers
./gradlew build             # both, plus the reference documentation
```

## Published images

CI builds the image with Jib and publishes `bloomper/fgv-spexgram-server` for `linux/amd64` and `linux/arm64`.
Since the sample data makes videos with ffmpeg, Jib builds on `bloomper/fgv-spexgram-server-base:25`
(`eclipse-temurin:25-jre` plus ffmpeg, from `base/Dockerfile`), which CI rebuilds and publishes first:

| Trigger              | Tags                              |
|----------------------|-----------------------------------|
| push to `develop`    | `develop`, `sha-<commit>`         |
| push of tag `vX.Y.Z` | `X.Y.Z`, `latest`, `sha-<commit>` |

To release, set `version` in `build.gradle` to `X.Y.Z`, merge to `main`, then `git tag vX.Y.Z && git push origin vX.Y.Z`.
The build fails if the tag doesn't match the version or if `X.Y.Z` is already on Docker Hub.

## Contributing

Contributors to this project agree to uphold its [code of conduct][6].

You can contribute to Spexgram Server by:

- Opening a [pull request][7]. Please see the [contributor guidelines][8] for details

## License

Spexgram Server is open source software released under the [Apache 2.0 license][9].

[1]: https://github.com/bloomper/fgv-keycloak
[2]: https://github.com/bloomper/fgv-spexgram-web
[3]: https://github.com/bloomper/fgv-spexgram-deploy
[4]: https://github.com/bloomper/fgv-spexgram-spec
[5]: https://gradle.org
[6]: CODE_OF_CONDUCT.md
[7]: https://help.github.com/articles/using-pull-requests/
[8]: CONTRIBUTING.md
[9]: https://www.apache.org/licenses/LICENSE-2.0.html
[10]: https://api.spexgram.fgv.local.nu:8443/docs/index.html
[11]: https://immich.app/
