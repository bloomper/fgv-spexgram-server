# syntax=docker.io/docker/dockerfile:1

# Base image for Jib (see build.gradle), which can't add operating system packages itself: ffmpeg makes the sample
# data's videos; the fonts are for their burnt-in titles. Immich, not the API, processes real uploads.
# CI publishes it as bloomper/fgv-spexgram-server-base:25 before each image build, so ffmpeg gets security updates.
FROM eclipse-temurin:25-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg fonts-dejavu-core fontconfig \
    && rm -rf /var/lib/apt/lists/*
