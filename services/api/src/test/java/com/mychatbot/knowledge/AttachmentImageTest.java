package com.mychatbot.knowledge;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class AttachmentImageTest {
    @TempDir Path temporary;

    @Test void largePngAndJpegKeepTheirFormatAndShrinkWithReadableDimensions() throws Exception {
        for (String extension : new String[] {"png", "jpeg"}) {
            var image = new BufferedImage(2400, 1200, BufferedImage.TYPE_INT_RGB);
            Path path = temporary.resolve("image." + extension);
            ImageIO.write(image, extension, path.toFile());
            long before = Files.size(path);
            AttachmentImage.normalize(path, extension);
            var saved = ImageIO.read(path.toFile());
            assertThat(saved.getWidth()).isEqualTo(1920);
            assertThat(saved.getHeight()).isEqualTo(960);
            assertThat(Files.size(path)).isLessThan(before);
        }
    }
}
