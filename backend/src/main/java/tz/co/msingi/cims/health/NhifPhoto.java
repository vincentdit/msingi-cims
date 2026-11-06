package tz.co.msingi.cims.health;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/** The student's photo as NHIF wants it: a JPEG 171 pixels wide and 179 tall (cropped to that shape, not stretched), in Base64. */
final class NhifPhoto {

    static final int WIDTH = 171;
    static final int HEIGHT = 179;

    private NhifPhoto() {
    }

    /** Null when there is no photo or it cannot be read as an image. */
    static String base64(byte[] photo) {
        if (photo == null || photo.length == 0) return null;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(photo));
            if (src == null) return null;
            double target = (double) WIDTH / HEIGHT;
            int w = src.getWidth(), h = src.getHeight();
            int cw = w, ch = h, x = 0, y = 0;
            if ((double) w / h > target) {
                cw = (int) Math.round(h * target);
                x = (w - cw) / 2;
            } else {
                ch = (int) Math.round(w / target);
                y = Math.max(0, (h - ch) / 4);   // keep the face: crop more from the bottom than the top
            }
            BufferedImage out = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, WIDTH, HEIGHT, x, y, x + cw, y + ch, java.awt.Color.WHITE, null);
            g.dispose();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.88f);
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bytes)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(out, null, null), p);
            } finally {
                writer.dispose();
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
