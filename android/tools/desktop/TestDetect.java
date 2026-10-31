import vn.quettailieu.scanner.core.DocDetector;
import vn.quettailieu.scanner.core.Enhancer;
import vn.quettailieu.scanner.core.Geom;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/** Kiểm thử bộ phát hiện + nắn phẳng + lọc trên máy tính. */
public class TestDetect {
    public static void main(String[] args) throws Exception {
        File outDir = new File(args[0]);
        outDir.mkdirs();
        boolean doScan = Boolean.parseBoolean(System.getProperty("scan", "true"));
        for (int a = 1; a < args.length; a++) {
            File f = new File(args[a]);
            BufferedImage img = ImageIO.read(f);
            int W = img.getWidth(), H = img.getHeight();
            int[] full = img.getRGB(0, 0, W, H, null, 0, W);
            // giống Android: ảnh phát hiện có cạnh dài ~1024
            float s = Math.min(1f, 1024f / Math.max(W, H));
            int w = Math.round(W * s), h = Math.round(H * s);
            BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g0 = small.createGraphics();
            g0.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g0.drawImage(img, 0, 0, w, h, null);
            g0.dispose();
            int[] px = small.getRGB(0, 0, w, h, null, 0, w);
            long t0 = System.nanoTime();
            DocDetector.Result r = DocDetector.detect(px, w, h);
            long t1 = System.nanoTime();
            String name = f.getName().replaceAll("\\.[a-z]+$", "");
            System.out.printf("%s: %.1f ms  %s%n", name, (t1 - t0) / 1e6, r == null ? "null" : r.debug);
            BufferedImage vis = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = vis.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float[] q = null;
            if (r != null && r.quad != null) {
                q = Geom.scale(r.quad, W / (float) w, H / (float) h);
                g.setColor(new Color(0, 181, 150));
                g.setStroke(new BasicStroke(6));
                for (int i = 0; i < 4; i++) {
                    int j = (i + 1) % 4;
                    g.drawLine(Math.round(q[2 * i]), Math.round(q[2 * i + 1]), Math.round(q[2 * j]), Math.round(q[2 * j + 1]));
                }
                for (int i = 0; i < 4; i++) {
                    g.fillOval(Math.round(q[2 * i]) - 14, Math.round(q[2 * i + 1]) - 14, 28, 28);
                }
            }
            g.dispose();
            ImageIO.write(vis, "png", new File(outDir, "det_" + name + ".png"));
            if (q != null && doScan) {
                int[] os = Geom.outputSize(q, W, H, -1, true, 2400);
                double ar = Geom.estimateAspect(q, W, H, -1);
                long t2 = System.nanoTime();
                int[] warped = Geom.warp(full, W, H, q, os[0], os[1]);
                long t3 = System.nanoTime();
                int[] enh = warped.clone();
                Enhancer.apply(enh, os[0], os[1], Enhancer.MAGIC);
                long t4 = System.nanoTime();
                System.out.printf("   aspect=%.4f out=%dx%d warp=%.0fms enh=%.0fms%n", ar, os[0], os[1], (t3 - t2) / 1e6, (t4 - t3) / 1e6);
                BufferedImage o = new BufferedImage(os[0] * 2, os[1], BufferedImage.TYPE_INT_RGB);
                o.setRGB(0, 0, os[0], os[1], warped, 0, os[0]);
                o.setRGB(os[0], 0, os[0], os[1], enh, 0, os[0]);
                ImageIO.write(o, "png", new File(outDir, "scan_" + name + ".png"));
            }
        }
    }
}
