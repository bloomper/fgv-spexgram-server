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


package nu.fgv.spexgram.server.util.data;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.imaging.ImagingException;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Generates sample files, never real photos (5.9 of the spec): images with the album's title, a number and a coloured
 * pattern, as JPEG with metadata for the date taken, an optional position and rotation, or as PNG; and short videos of
 * a test pattern with a tone, made with ffmpeg. Each image differs, so that Immich never takes one for a duplicate.
 *
 * <p>HEIC is left out: ffmpeg, the only encoder in the image, cannot write it.</p>
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
final class SampleMedia {

    static final DateTimeFormatter EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");
    private static final long FFMPEG_TIMEOUT_MINUTES = 5;

    private SampleMedia() {
    }

    /**
     * An image with the title, the number and a pattern of its own.
     *
     * @param hue     the album's colour, 0 to 1
     * @param number  the item's number, which also seeds the pattern
     * @param rotated whether the pixels lie on their side, to be turned by the orientation in the metadata
     */
    static BufferedImage image(final int width, final int height, final float hue, final int number, final String title,
                               final boolean rotated) {
        final int w = rotated ? height : width;
        final int h = rotated ? width : height;
        final BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        final Random random = new Random(number * 7919L + title.hashCode());

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, Color.getHSBColor(hue, 0.55f, 0.9f), w, h, Color.getHSBColor((hue + 0.15f) % 1, 0.7f, 0.45f)));
            g.fillRect(0, 0, w, h);
            g.setStroke(new BasicStroke(Math.max(2, w / 200f)));
            for (int i = 0; i < 12; i++) {
                final int size = (int) (Math.min(w, h) * (0.08 + random.nextDouble() * 0.25));

                g.setColor(Color.getHSBColor((hue + random.nextFloat() * 0.3f) % 1, 0.4f + random.nextFloat() * 0.5f, 0.6f + random.nextFloat() * 0.4f));
                if (random.nextBoolean()) {
                    g.fillOval(random.nextInt(w), random.nextInt(h), size, size);
                } else {
                    g.drawRect(random.nextInt(w), random.nextInt(h), size, size);
                }
            }
            if (rotated) {
                // Drawn turned, so that it reads right once a viewer applies the orientation
                g.rotate(-Math.PI / 2);
                g.translate(-h, 0);
            }

            final int textWidth = rotated ? h : w;
            final int textHeight = rotated ? w : h;

            g.setColor(new Color(0, 0, 0, 140));
            g.fillRect(0, textHeight - textHeight / 4, textWidth, textHeight / 4);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, textHeight / 14)));
            g.drawString(title, textWidth / 30, textHeight - textHeight / 7);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, textHeight / 20)));
            g.drawString("#" + number, textWidth / 30, textHeight - textHeight / 30);
        } finally {
            g.dispose();
        }
        return image;
    }

    /**
     * Writes a JPEG with the date taken, and the position and orientation when given.
     */
    static void jpeg(final BufferedImage image, final Path file, final LocalDateTime taken, final double @Nullable [] position,
                     final boolean rotated) throws IOException {
        final ByteArrayOutputStream plain = new ByteArrayOutputStream();

        if (!ImageIO.write(image, "jpg", plain)) {
            throw new IOException("No JPEG writer");
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            final TiffOutputSet metadata = new TiffOutputSet();

            metadata.getOrCreateExifDirectory().add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, EXIF_DATE.format(taken));
            if (position != null) {
                metadata.setGpsInDegrees(position[1], position[0]);
            }
            if (rotated) {
                // 6: the stored pixels must be turned 90 degrees clockwise
                metadata.getOrCreateRootDirectory().add(TiffTagConstants.TIFF_TAG_ORIENTATION, (short) 6);
            }
            new ExifRewriter().updateExifMetadataLossless(plain.toByteArray(), out, metadata);
        } catch (final ImagingException e) {
            throw new IOException(e);
        }
    }

    static void png(final BufferedImage image, final Path file) throws IOException {
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("No PNG writer");
        }
    }

    /**
     * The sample videos: H.264 in MP4 of 5 to 30 seconds, which Immich can play as they are; MPEG-4 in AVI, which it
     * must transcode; one longer than {@code longMinutes}, to be rejected; and a file that is not a video at all.
     */
    static List<Video> videos(final Path folder, final int longMinutes) throws IOException {
        final List<Video> videos = new ArrayList<>();

        videos.add(new Video("Repetition av öppningsnumret", ffmpeg(folder, "repetition.mp4", 640, 360, 25, 12,
                List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac"))));
        videos.add(new Video("Finalen från kulisserna", ffmpeg(folder, "finalen.mp4", 640, 360, 25, 30,
                List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac"))));
        videos.add(new Video("Gammal film från en mobil", ffmpeg(folder, "gammal-film.avi", 480, 270, 25, 5,
                List.of("-c:v", "mpeg4", "-q:v", "5", "-c:a", "mp3"))));
        videos.add(new Video("Hela föreställningen, för lång", ffmpeg(folder, "for-lang.mp4", 160, 90, 1, longMinutes * 60 + 60,
                List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "16k"))));

        final Path text = folder.resolve("inte-en-bild.txt");

        Files.writeString(text, "Det här är ingen bild.\n", StandardCharsets.UTF_8);
        videos.add(new Video("Inte en bild", text));
        return videos;
    }

    private static Path ffmpeg(final Path folder, final String name, final int width, final int height, final int rate,
                               final int seconds, final List<String> codec) throws IOException {
        final Path file = folder.resolve(name);
        final List<String> command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=%dx%d:rate=%d:duration=%d".formatted(width, height, rate, seconds),
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=22050:duration=" + seconds,
                "-shortest"));

        command.addAll(codec);
        command.add(file.toString());

        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

        try {
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!process.waitFor(FFMPEG_TIMEOUT_MINUTES, TimeUnit.MINUTES) || process.exitValue() != 0) {
                throw new IOException("ffmpeg failed to make " + name + ": " + output);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            process.destroy();
        }
        return file;
    }

    /**
     * A generated video, or another file to upload.
     */
    record Video(String caption, Path file) {
    }
}
