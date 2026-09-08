package com.zhongbai233.mcandroidphone.phone;

/** Aspect fit and inverse homography. All coordinates use top-left origin. No Minecraft dependency. */
public final class PhoneGeometry {
    // Phone-only shell: 9:16 face and a narrow, equal physical bezel.
    public static final int PHONE_FACE_WIDTH = 252;
    public static final float PHONE_BEZEL = .012F;
    static final double PHONE_SURFACE_ASPECT = (PHONE_FACE_WIDTH / 448.0 + 2.0 * PHONE_BEZEL)
        / (1.0 + 2.0 * PHONE_BEZEL);
    private PhoneGeometry() {}

    public record Point(double u, double v) {
        public Point clamped() { return new Point(clamp(u), clamp(v)); }
    }

    public record Fit(double left, double top, double width, double height) {
        public Point map(Point p, boolean captured) {
            if (p == null) return null;
            double u = (p.u - left) / width, v = (p.v - top) / height;
            // Inverse perspective can put an exact screen edge a few ulps outside the rectangle.
            double epsilon = 1e-9;
            if (!captured && (u < -epsilon || u > 1 + epsilon || v < -epsilon || v > 1 + epsilon)) return null;
            return new Point(clamp(u), clamp(v));
        }
    }

    public record HandLayout(double x, double y, double z, double scale) {}

    /** Phone-only pose in the 70-degree first-person camera used by Minecraft. */
    public static HandLayout focusedHand(double viewportAspect, int guiWidth, int guiHeight,
                                         int headerHeight, int footerHeight) {
        return focusedHand(viewportAspect,guiWidth,guiHeight,headerHeight,footerHeight,0);
    }
    public static HandLayout focusedHand(double viewportAspect,int guiWidth,int guiHeight,
                                         int headerHeight,int footerHeight,double rotation) {
        if (!(viewportAspect > 0) || guiWidth <= 0 || guiHeight <= 0 || !Double.isFinite(rotation))
            throw new IllegalArgumentException("Invalid viewport");
        double margin = Math.min(8, guiWidth * .05);
        double top = Math.min(guiHeight * .4, Math.max(headerHeight, guiHeight * .14));
        double bottom = Math.min(guiHeight * .4, Math.max(footerHeight, guiHeight * .12));
        // Bounds include the bezel, thickness and four-degree hover tilt; scale stays uniform.
        double c=Math.abs(Math.cos(Math.toRadians(rotation))),s=Math.abs(Math.sin(Math.toRadians(rotation)));
        double bodyWidth = .38*c+.60*s, bodyHeight = .60*c+.38*s, bodyDepth = .06;
        double tanHalfFov = Math.tan(Math.toRadians(35)), distance = 1.09;
        double availableHeight = Math.min(guiHeight * .66, guiHeight - top - bottom);
        double portraitHeight = Math.min(availableHeight,
            (guiWidth-2*margin) / (.38/.60 * guiWidth/(viewportAspect*guiHeight)));
        double fraction=portraitHeight/guiHeight;
        double portraitScale=fraction*2*tanHalfFov*distance/(.60+fraction*2*tanHalfFov*bodyDepth);
        double fitHeight=Math.min(availableHeight,
            (guiWidth-2*margin)/(bodyWidth/bodyHeight*guiWidth/(viewportAspect*guiHeight)));
        fraction=fitHeight/guiHeight;
        // Rotating a physical phone must not enlarge it. Shrink only when a small viewport needs it.
        double scale=Math.min(portraitScale,
            fraction*2*tanHalfFov*distance/(bodyHeight+fraction*2*tanHalfFov*bodyDepth));
        double nearDepth=distance-bodyDepth*scale;
        double height=bodyHeight*scale/(2*tanHalfFov*nearDepth)*guiHeight;
        double width=bodyWidth*scale/(2*tanHalfFov*nearDepth*viewportAspect)*guiWidth;
        double centerX=Math.max(margin+width/2,Math.min(guiWidth*.70,guiWidth-margin-width/2));
        // The device renderer owns grip-pivot motion; do not counter-translate it here.
        double centerY=(top+guiHeight-bottom)/2;
        return new HandLayout((2*centerX/guiWidth-1)*nearDepth*tanHalfFov*viewportAspect,
            (1-2*centerY/guiHeight)*nearDepth*tanHalfFov,-distance,scale);
    }

    /** A fixed 9:16 panel surrounded by equal model-space bezels; result uses outer-shell UVs. */
    public static Fit phoneFit(double surfaceAspect, int width, int height) {
        double panelAspect = 9.0 / 16;
        // Normalize the outer height to one. Solve (width-2b)/(height-2b)=9/16.
        double bezel = (surfaceAspect - panelAspect) / (2 * (1 - panelAspect));
        if (!Double.isFinite(bezel) || bezel <= 0 || bezel * 2 >= Math.min(surfaceAspect, 1))
            throw new IllegalArgumentException("Phone shell must surround a portrait panel");
        double left = bezel / surfaceAspect, top = bezel;
        double panelWidth = 1 - 2 * left, panelHeight = 1 - 2 * top;
        Fit guest = fit(panelAspect, width, height);
        return new Fit(left + panelWidth * guest.left(), top + panelHeight * guest.top(),
            panelWidth * guest.width(), panelHeight * guest.height());
    }

    public static Fit fit(double surfaceAspect, int width, int height) {
        if (!(surfaceAspect > 0) || width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid aspect");
        double imageAspect = width / (double) height;
        double w = Math.min(1, imageAspect / surfaceAspect);
        double h = Math.min(1, surfaceAspect / imageAspect);
        return new Fit((1 - w) / 2, (1 - h) / 2, w, h);
    }

    /** Forward homography used for grip and regression checks on tilted/rotated quads. */
    public static Point project(double[] x,double[] y,double u,double v) {
        double dx1=x[1]-x[2],dx2=x[3]-x[2],dx3=x[0]-x[1]+x[2]-x[3];
        double dy1=y[1]-y[2],dy2=y[3]-y[2],dy3=y[0]-y[1]+y[2]-y[3];
        double det=dx1*dy2-dx2*dy1;if(Math.abs(det)<1e-8)return null;
        double g=(dx3*dy2-dx2*dy3)/det,h=(dx1*dy3-dx3*dy1)/det,w=g*u+h*v+1;
        if(Math.abs(w)<1e-9)return null;
        double px=((x[1]-x[0]+g*x[1])*u+(x[3]-x[0]+h*x[3])*v+x[0])/w;
        double py=((y[1]-y[0]+g*y[1])*u+(y[3]-y[0]+h*y[3])*v+y[0])/w;
        return Double.isFinite(px)&&Double.isFinite(py)?new Point(px,py):null;
    }

    /** Quad order: top-left, top-right, bottom-right, bottom-left. Rejects degenerate surfaces. */
    public static Point unproject(double[] x, double[] y, double px, double py) {
        double dx1 = x[1] - x[2], dx2 = x[3] - x[2], dx3 = x[0] - x[1] + x[2] - x[3];
        double dy1 = y[1] - y[2], dy2 = y[3] - y[2], dy3 = y[0] - y[1] + y[2] - y[3];
        double det = dx1 * dy2 - dx2 * dy1;
        if (Math.abs(det) < 1e-8) return null;
        double g = (dx3 * dy2 - dx2 * dy3) / det;
        double h = (dx1 * dy3 - dx3 * dy1) / det;
        double a = x[1] - x[0] + g * x[1], b = x[3] - x[0] + h * x[3];
        double d = y[1] - y[0] + g * y[1], e = y[3] - y[0] + h * y[3];
        double au = a - px * g, av = b - px * h, bu = d - py * g, bv = e - py * h;
        double determinant = au * bv - av * bu;
        if (Math.abs(determinant) < 1e-8) return null;
        double u = ((px - x[0]) * bv - av * (py - y[0])) / determinant;
        double v = (au * (py - y[0]) - (px - x[0]) * bu) / determinant;
        return Double.isFinite(u) && Double.isFinite(v) ? new Point(u, v) : null;
    }

    private static double clamp(double value) { return Math.max(0, Math.min(1, value)); }
}
