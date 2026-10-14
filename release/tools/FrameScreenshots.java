import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Turns the rendered screens (release/store/screenshots/raw-render) into Play Store screenshots:
 * 1080 x 1920 PNG, a headline and subline above the screen on a dark Nourio background.
 *
 * Usage (from the FoodTracker folder):
 *   "C:\Program Files\Android\Android Studio\jbr\bin\java" release\tools\FrameScreenshots.java
 */
public class FrameScreenshots {
    static final Color INK = new Color(0x0E1A13);
    static final Color MINT = new Color(0x3DDC97);
    static final Color MUTED = new Color(0xB7C7BE);

    // File name (without .png) -> headline | subline
    static final Map<String, String[]> CAPTIONS = new LinkedHashMap<>();
    static {
        CAPTIONS.put("01-home", new String[]{"Your day as a report card", "Grades for calories, protein, fiber, water and steps"});
        CAPTIONS.put("02-dashboard", new String[]{"Move more, every day", "Steps, burn and your week at a glance"});
        CAPTIONS.put("03-intake", new String[]{"Know what's on your plate", "Carbs, protein, fat and every nutrient"});
        CAPTIONS.put("04-burned", new String[]{"Eaten vs burned", "Clear charts for calories and steps"});
        CAPTIONS.put("05-insights", new String[]{"Tips from your own data", "Accept a tip and your goals update"});
        CAPTIONS.put("06-insights-links", new String[]{"Find what affects you", "Links between food, sleep, steps and weight"});
        CAPTIONS.put("07-profile", new String[]{"Your journey to your goal", "Progress and streaks in one place"});
    }

    public static void main(String[] args) throws Exception {
        File in = new File(args.length > 0 ? args[0] : "release/store/screenshots/raw-render");
        File out = new File(args.length > 1 ? args[1] : "release/store/screenshots");
        out.mkdirs();
        for (Map.Entry<String, String[]> e : CAPTIONS.entrySet()) {
            File src = new File(in, e.getKey() + ".png");
            if (!src.exists()) { System.out.println("missing " + src); continue; }
            BufferedImage shot = ImageIO.read(src);
            BufferedImage frame = render(shot, e.getValue()[0], e.getValue()[1]);
            File target = new File(out, e.getKey() + ".png");
            ImageIO.write(frame, "png", target);
            System.out.println(target + "  " + frame.getWidth() + "x" + frame.getHeight());
        }
    }

    static BufferedImage render(BufferedImage shot, String title, String subtitle) {
        int W = 1080, H = 1920;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);    // 24-bit, no alpha
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Background: ink with a soft mint glow at the top
        g.setColor(INK);
        g.fillRect(0, 0, W, H);
        g.setPaint(new RadialGradientPaint(new Point(W / 2, 120), 900f, new float[]{0f, 1f},
            new Color[]{new Color(0x3D, 0xDC, 0x97, 70), new Color(0x0E, 0x1A, 0x13, 0)}));
        g.fillRect(0, 0, W, H);

        // Headline and subline, centred
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 68));
        center(g, title, W, 175);
        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.PLAIN, 36));
        center(g, subtitle, W, 245);

        // The screen, scaled, with rounded corners and a mint outline
        int shotH = 1560, shotW = Math.round((float) shotH * shot.getWidth() / shot.getHeight());
        int x = (W - shotW) / 2, y = 320;
        Shape round = new RoundRectangle2D.Float(x, y, shotW, shotH, 56, 56);
        Shape old = g.getClip();
        g.setClip(round);
        g.drawImage(shot, x, y, shotW, shotH, null);
        g.setClip(old);
        g.setStroke(new BasicStroke(4f));
        g.setColor(new Color(0x3D, 0xDC, 0x97, 140));
        g.draw(round);
        g.dispose();
        return img;
    }

    static void center(Graphics2D g, String text, int width, int baseline) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (width - fm.stringWidth(text)) / 2, baseline);
    }
}
