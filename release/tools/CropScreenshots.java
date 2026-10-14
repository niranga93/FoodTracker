import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Makes phone screenshots fit Google Play's rules: the long side may be at most twice the short side.
 * Crops from the top (removing the status bar) to a 1:2 ratio, then scales to 1080 x 2160 if needed.
 *
 * Usage (from the FoodTracker folder):
 *   "C:\Program Files\Android\Android Studio\jbr\bin\java" release\tools\CropScreenshots.java <in-folder> <out-folder>
 */
public class CropScreenshots {
    public static void main(String[] args) throws Exception {
        File in = new File(args.length > 0 ? args[0] : "release/store/screenshots/raw");
        File out = new File(args.length > 1 ? args[1] : "release/store/screenshots");
        out.mkdirs();
        File[] files = in.listFiles((d, n) -> n.toLowerCase().endsWith(".png") || n.toLowerCase().endsWith(".jpg"));
        if (files == null || files.length == 0) { System.out.println("No screenshots in " + in); return; }
        for (File f : files) {
            BufferedImage src = ImageIO.read(f);
            int w = src.getWidth(), h = src.getHeight();
            int maxH = w * 2;
            // Drop the status bar (about 4% of the height) first, then trim the bottom if still too tall
            int top = h > maxH ? Math.min((int) (h * 0.04), h - maxH) : 0;
            int cropH = Math.min(h - top, maxH);
            BufferedImage cropped = src.getSubimage(0, top, w, cropH);
            int outW = 1080, outH = Math.round(1080f * cropH / w);
            BufferedImage dst = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);   // 24-bit, no alpha
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(cropped, 0, 0, outW, outH, null);
            g.dispose();
            File target = new File(out, f.getName().replaceAll("\\.(jpg|JPG)$", ".png"));
            ImageIO.write(dst, "png", target);
            System.out.println(target + "  " + outW + "x" + outH);
        }
    }
}
