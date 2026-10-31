import vn.quettailieu.scanner.core.DocDetector;
import vn.quettailieu.scanner.core.Geom;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

public class EvalSynth {
    public static void main(String[] a) throws Exception {
        BufferedReader br = new BufferedReader(new FileReader(a[0]));
        File outDir = new File(a[1]); outDir.mkdirs();
        String line; int ok = 0, tot = 0; double sumErr = 0;
        while ((line = br.readLine()) != null) {
            String[] p = line.trim().split(" ");
            float[] gt = new float[8];
            for (int i = 0; i < 8; i++) gt[i] = Float.parseFloat(p[1 + i]);
            File imgFile = new File(p[0]);
            if (!imgFile.isAbsolute()) imgFile = new File(new File(a[0]).getAbsoluteFile().getParentFile(), p[0]);
            BufferedImage img = ImageIO.read(imgFile);
            int W = img.getWidth(), H = img.getHeight();
            float s = Math.min(1f, 1024f / Math.max(W, H));
            int w = Math.round(W * s), h = Math.round(H * s);
            BufferedImage sm = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g0 = sm.createGraphics();
            g0.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g0.drawImage(img, 0, 0, w, h, null); g0.dispose();
            int[] px = sm.getRGB(0, 0, w, h, null, 0, w);
            long t0 = System.nanoTime();
            DocDetector.Result r = DocDetector.detect(px, w, h);
            long t1 = System.nanoTime();
            double diag = Math.hypot(W, H);
            double err = 1;
            float[] q = null;
            if (r != null && r.quad != null) {
                q = Geom.scale(r.quad, W / (float) w, H / (float) h);
                err = 0;
                for (int i = 0; i < 4; i++) err = Math.max(err, Math.hypot(q[2*i]-gt[2*i], q[2*i+1]-gt[2*i+1]) / diag);
            }
            tot++; if (err < 0.01) ok++; sumErr += Math.min(err, 0.2);
            System.out.printf("%-40s err=%.4f (%.1fpx) %.0fms %s%n", imgFile.getName(), err, err*diag, (t1-t0)/1e6, r==null?"":r.debug);
            BufferedImage vis = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = vis.createGraphics(); g.drawImage(img, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(3)); g.setColor(Color.RED);
            for (int i = 0; i < 4; i++) { int j=(i+1)%4; g.drawLine((int)gt[2*i],(int)gt[2*i+1],(int)gt[2*j],(int)gt[2*j+1]); }
            if (q != null) { g.setColor(new Color(0,181,150)); g.setStroke(new BasicStroke(5));
              for (int i = 0; i < 4; i++) { int j=(i+1)%4; g.drawLine(Math.round(q[2*i]),Math.round(q[2*i+1]),Math.round(q[2*j]),Math.round(q[2*j+1])); } }
            g.dispose();
            ImageIO.write(vis, "png", new File(outDir, "det_" + imgFile.getName().replace(".jpg","") + ".png"));
        }
        System.out.printf("OK(<1%% diag): %d/%d  meanErr=%.4f%n", ok, tot, sumErr/tot);
        if (System.getProperty("minOk") != null && ok < Integer.parseInt(System.getProperty("minOk"))) System.exit(1);
    }
}
