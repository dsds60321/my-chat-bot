package com.mychatbot.knowledge;

import java.io.IOException;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.IIOImage;
import javax.imageio.ImageWriteParam;
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
        int longest = Math.max(image.getWidth(), image.getHeight());
        if (longest > 1920) {
            double ratio = 1920.0 / longest;
            var scaled = new BufferedImage(Math.max(1, (int) Math.round(image.getWidth() * ratio)),
                    Math.max(1, (int) Math.round(image.getHeight() * ratio)),
                    image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            var graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(image, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
            } finally {
                graphics.dispose();
            }
            image = scaled;
        }
        // Re-encode pixels with compression and discard metadata supplied by the uploader.
        Files.delete(path);
        var writer = ImageIO.getImageWritersByFormatName(format).next();
        try (var output = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(format.equals("jpeg") ? 0.85f : 0.0f);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally {
            writer.dispose();
        }
        if (Files.size(path) > MAX_BYTES) throw new IOException("image-size");
    }
}
