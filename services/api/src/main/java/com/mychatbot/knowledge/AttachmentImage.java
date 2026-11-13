package com.mychatbot.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

final class AttachmentImage {
    static final int MAX_BYTES = 8 * 1024 * 1024;

    static void normalize(Path path, String extension) throws IOException {
        String format = extension.equals("png") ? "png" : "jpeg";
        java.awt.image.BufferedImage image;
        try (var input = ImageIO.createImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("invalid-image");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!reader.getFormatName().toLowerCase(Locale.ROOT).equals(format)
                        || (long) reader.getWidth(0) * reader.getHeight(0) > 12_000_000) {
                    throw new IOException("unsupported-image");
                }
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        // Re-encode decoded pixels so uploaded metadata and embedded payloads are discarded.
        try (var output = Files.newOutputStream(path)) {
            if (!ImageIO.write(image, format, output)) throw new IOException("image-encoding");
        }
        if (Files.size(path) > MAX_BYTES) throw new IOException("image-size");
    }
}
