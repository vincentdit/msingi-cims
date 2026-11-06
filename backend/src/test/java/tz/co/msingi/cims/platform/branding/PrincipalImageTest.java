package tz.co.msingi.cims.platform.branding;

import org.junit.jupiter.api.Test;
import tz.co.msingi.cims.common.document.OfficialMarks;
import tz.co.msingi.cims.common.exception.BusinessRuleException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scanned signatures and stamps are made ready for print: paper cleared, cropped to the ink, reduced, stored as PNG. */
class PrincipalImageTest {

    /** A "scan": white paper (slightly grey, as scanners give) with a dark stroke in the middle. */
    static byte[] scan(int w, int h, String format) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(new Color(246, 246, 244));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(20, 30, 90));
        g.fillRect(w / 4, h / 3, w / 2, h / 6);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    void paperBecomesSeeThroughAndTheImageIsCroppedToTheInk() throws Exception {
        OfficialMarks.Image i = PrincipalService.prepare(scan(800, 600, "jpg"), false);
        assertThat(i.type()).isEqualTo("image/png");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(i.data()));
        assertThat(img.getWidth()).isBetween(400, 440);           // the 400-pixel stroke plus a small margin
        assertThat(img.getHeight()).isBetween(100, 140);
        assertThat(img.getRGB(0, 0) >>> 24).isEqualTo(0);         // the paper in the margin is transparent
        assertThat(img.getRGB(img.getWidth() / 2, img.getHeight() / 2) >>> 24).isEqualTo(255);   // the ink is solid
    }

    @Test
    void largeScansAreReducedAndTheBackgroundCanBeKept() throws Exception {
        OfficialMarks.Image i = PrincipalService.prepare(scan(4000, 3000, "png"), true);
        assertThat(i.width()).isEqualTo(PrincipalService.STORE_WIDTH);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(i.data()));
        assertThat(img.getRGB(0, 0) >>> 24).isEqualTo(255);       // kept: the paper stays
    }

    @Test
    void refusesWhatCannotBeASignature() throws Exception {
        assertThatThrownBy(() -> PrincipalService.prepare(new byte[]{1, 2, 3}, false)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PNG or JPEG");
        assertThatThrownBy(() -> PrincipalService.prepare(scan(100, 60, "png"), false)).hasMessageContaining("150 pixels");
        BufferedImage blank = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        var g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 200);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(blank, "png", out);
        assertThatThrownBy(() -> PrincipalService.prepare(out.toByteArray(), false)).hasMessageContaining("No ink");
    }
}
