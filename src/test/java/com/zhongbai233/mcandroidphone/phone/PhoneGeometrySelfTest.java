package com.zhongbai233.mcandroidphone.phone;

public final class PhoneGeometrySelfTest {
    public static void main(String[] args) {
        // Project a UV grid with nonzero perspective, then recover every point.
        double[] x = new double[4], y = new double[4];
        double[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (int i = 0; i < 4; i++) {
            var p = forward(corners[i][0], corners[i][1]); x[i] = p[0]; y[i] = p[1];
        }
        for (int u = 0; u <= 10; u++) for (int v = 0; v <= 10; v++) {
            var screen = forward(u / 10.0, v / 10.0);
            var recovered = PhoneGeometry.unproject(x, y, screen[0], screen[1]);
            require(recovered != null, "perspective inverse missing");
            near(recovered.u(), u / 10.0); near(recovered.v(), v / 10.0);
        }
        var fit = PhoneGeometry.fit(0.6, 1080, 1920);
        near(fit.width(), 0.9375); near(fit.height(), 1);
        require(fit.map(new PhoneGeometry.Point(0, .5), false) == null, "bar touch rejected");
        near(fit.map(new PhoneGeometry.Point(-3, .5), true).u(), 0);
        near(fit.map(new PhoneGeometry.Point(4, .5), true).u(), 1);
        require(PhoneGeometry.unproject(new double[4], new double[4], 1, 2) == null, "degenerate quad rejected");
        checkTiltAndRotation();
        checkPhoneBezel();
        checkHandLayouts();
        System.out.println("Phone geometry: equal bezels, inner-screen touch, perspective, letterbox, captured drag and viewport margins passed.");
    }

    private static void checkTiltAndRotation() {
        for(double angle:new double[]{0,-35,-90})for(double tilt:new double[]{-4,0,4}) {
            double[] x=new double[4],y=new double[4];
            double c=Math.cos(Math.toRadians(tilt)),s=Math.sin(Math.toRadians(tilt));
            double cr=Math.cos(Math.toRadians(angle)),sr=Math.sin(Math.toRadians(angle));
            for(int i=0;i<4;i++) {
                double px=i==0||i==3?-.3:.3,py=i<2?.5:-.5,pz=.025;
                double rx=c*px-s*pz,rz=s*px+c*pz;
                double ry=c*py-s*rz;rz=s*py+c*rz;
                double vx=cr*rx-sr*ry+.3,vy=sr*rx+cr*ry,vz=rz-1.1;
                double tan=Math.tan(Math.toRadians(35));
                x[i]=(.5+vx/(-vz*tan*1.77)*.5)*854;y[i]=(.5-vy/(-vz*tan)*.5)*480;
            }
            for(double u:new double[]{0,.2,.5,.8,1})for(double v:new double[]{0,.2,.5,.8,1}) {
                var projected=PhoneGeometry.project(x,y,u,v);require(projected!=null,"project tilted surface");
                var local=PhoneGeometry.unproject(x,y,projected.u(),projected.v());
                require(local!=null,"unproject rotated surface");near(local.u(),u);near(local.v(),v);
            }
        }
        var pose=new PhonePose();pose.hover(new PhoneGeometry.Point(1,0));
        for(int i=0;i<120;i++)pose.advance(true,1_000_000_000L+i*16_666_667L);
        require(pose.tiltX()<-3.9 && pose.tiltY()>3.9,"hover applies both tilt axes");
        pose.begin(100,0,0,0,4_000_000_000L);pose.drag(0,100,4_100_000_000L);pose.finish();
        for(int i=0;i<120;i++)pose.advance(true,4_100_000_000L+i*16_666_667L);
        require(Math.abs(pose.rotation()+90)<.01,"border rotation settles landscape");
        near(pose.landscapeShift(),.5);
        // The lower grip stays fixed in Y while the center drops during a -90 degree rotation.
        double px=(9.0/32+.012)*(PhonePose.PIVOT_U*2-1),py=.512*(1-PhonePose.PIVOT_V*2);
        double rotatedCenterY=px+py;
        require(rotatedCenterY<-.3,"landscape center drops toward hand without recentering");
        pose.reset();require(!pose.dragging() && pose.rotation()==0,"reset releases rotation");
    }

    private static void checkPhoneBezel() {
        double outerWidth = 9.0 / 16 + .024, outerHeight = 1.024;
        var panel = PhoneGeometry.phoneFit(outerWidth / outerHeight, 1080, 1920);
        double left = panel.left() * outerWidth, top = panel.top() * outerHeight;
        double right = (1 - panel.left() - panel.width()) * outerWidth;
        double bottom = (1 - panel.top() - panel.height()) * outerHeight;
        require(left > 0 && top > 0, "phone has a bezel on every side");
        near(left, right); near(left, top); near(left, bottom);
        near(left, .012);
        near(PhoneGeometry.PHONE_SURFACE_ASPECT, outerWidth / outerHeight);
        near(panel.width() * outerWidth / (panel.height() * outerHeight), 1080.0 / 1920);
        var center = panel.map(new PhoneGeometry.Point(.5, .5), false);
        require(center != null, "phone center accepts touch");
        near(center.u(), .5); near(center.v(), .5);

        PhoneGeometry.Point[] bezelPoints = {
            new PhoneGeometry.Point(panel.left() / 2, .5),
            new PhoneGeometry.Point(1 - panel.left() / 2, .5),
            new PhoneGeometry.Point(.5, panel.top() / 2),
            new PhoneGeometry.Point(.5, 1 - panel.top() / 2)};
        double[][] captured = {{0,.5}, {1,.5}, {.5,0}, {.5,1}};
        for (int i = 0; i < bezelPoints.length; i++) {
            require(panel.map(bezelPoints[i], false) == null, "bezel side " + i + " rejects a new touch");
            var drag = panel.map(bezelPoints[i], true);
            near(drag.u(), captured[i][0]); near(drag.v(), captured[i][1]);
        }

        var guest = PhoneGeometry.phoneFit(outerWidth / outerHeight, 1024, 768);
        near(guest.left(), panel.left()); near(guest.width(), panel.width());
        require(guest.top() > panel.top() && guest.height() < panel.height(), "4:3 guest fits inside fixed portrait panel");
        near(guest.top() + guest.height() / 2, .5);
        near(guest.width() * outerWidth / (guest.height() * outerHeight), 4.0 / 3);
        double barY = (panel.top() + guest.top()) / 2;
        require(guest.map(new PhoneGeometry.Point(.5, barY), false) == null, "inner top letterbox rejects touch");
        require(guest.map(new PhoneGeometry.Point(.5, 1 - barY), false) == null, "inner bottom letterbox rejects touch");
        checkProjectedInnerScreen(panel);
        checkProjectedInnerScreen(guest);
    }

    private static void checkProjectedInnerScreen(PhoneGeometry.Fit fit) {
        double[] x = new double[4], y = new double[4];
        for (int i = 0; i < 4; i++) {
            var p = forward(i == 1 || i == 2 ? 1 : 0, i >= 2 ? 1 : 0);
            x[i] = p[0]; y[i] = p[1];
        }
        // Render the entire guest UV grid inside the panel, then recover through the outer shell quad.
        // Include all four edges: the first and last guest rows/columns must remain reachable.
        for (int u = 0; u <= 10; u++) for (int v = 0; v <= 10; v++) {
            var screen = forward(fit.left() + fit.width() * u / 10.0, fit.top() + fit.height() * v / 10.0);
            var hit = fit.map(PhoneGeometry.unproject(x, y, screen[0], screen[1]), false);
            require(hit != null, "inner-screen edge rejected at " + u + "," + v);
            near(hit.u(), u / 10.0); near(hit.v(), v / 10.0);
        }
    }

    private static void checkHandLayouts() {
        // Wide, standard, portrait and small windows; include wrapped help at high GUI scale.
        int[][] viewports = {{1280,720,427,240,36}, {1920,1080,640,360,26},
            {3440,1440,860,360,26}, {320,640,160,320,56}, {640,480,214,160,46}};
        double halfWidth = (9.0 / 16 / 2 + .012) * .532;
        double halfHeight = .512 * .532;
        for (var viewport : viewports) {
            double aspect = viewport[0] / (double) viewport[1];
            int width = viewport[2], height = viewport[3], footer = viewport[4];
            var layout = PhoneGeometry.focusedHand(aspect, width, height, 24, footer);
            var landscape=PhoneGeometry.focusedHand(aspect,width,height,24,footer,-90);
            require(landscape.scale()<=layout.scale()+1e-9,"rotation never enlarges the device");
            if(width>height)near(landscape.y(),layout.y());
            if(width>height)near(landscape.scale(),layout.scale());
            require(layout.scale() < 1.92, "phone is smaller than MP4 focus pose");
            for (int pitch = -4; pitch <= 4; pitch += 4) for (int yaw = -4; yaw <= 4; yaw += 4) {
                double[] qx = new double[4], qy = new double[4];
                for (int i = 0; i < 4; i++) {
                    double u = i == 1 || i == 2 ? 1 : 0, v = i >= 2 ? 1 : 0;
                    var p = projectPhone(layout, aspect, width, height,
                        (2*u-1)*halfWidth, (1-2*v)*halfHeight, .022*.532, pitch, yaw);
                    qx[i] = p[0]; qy[i] = p[1];
                    require(p[0] >= 8 && p[0] <= width - 8, "phone fits horizontal margins");
                    require(p[1] >= 24 && p[1] <= height - footer, "phone clears status and help");
                }
                for (int u = 0; u <= 10; u++) for (int v = 0; v <= 10; v++) {
                    var p = projectPhone(layout, aspect, width, height,
                        (2*u/10.0-1)*halfWidth, (1-2*v/10.0)*halfHeight, .022*.532, pitch, yaw);
                    var hit = PhoneGeometry.unproject(qx, qy, p[0], p[1]);
                    require(hit != null, "resized touch projection missing");
                    near(hit.u(), u/10.0); near(hit.v(), v/10.0);
                }
            }
        }
    }

    private static double[] projectPhone(PhoneGeometry.HandLayout layout, double aspect, int width, int height,
                                         double x, double y, double z, double pitch, double yaw) {
        double ax = Math.toRadians(pitch), ay = Math.toRadians(yaw);
        // MP4's hover rotates around its grip point, below and to the right of screen center.
        double pivotX = (9.0/16)*(.58-.5)*.532, pivotY = (.5-.88)*.532;
        x -= pivotX; y -= pivotY;
        double rx = Math.cos(ay)*x + Math.sin(ay)*z, rz = -Math.sin(ay)*x + Math.cos(ay)*z;
        double ry = Math.cos(ax)*y - Math.sin(ax)*rz;
        rz = Math.sin(ax)*y + Math.cos(ax)*rz;
        rx += pivotX; ry += pivotY;
        double depth = -(layout.z() + rz*layout.scale());
        double nx = (layout.x() + rx*layout.scale()) / (depth*Math.tan(Math.toRadians(35))*aspect);
        double ny = (layout.y() + ry*layout.scale()) / (depth*Math.tan(Math.toRadians(35)));
        return new double[] {(nx+1)*width/2, (1-ny)*height/2};
    }
    private static double[] forward(double u, double v) {
        double w = .25 * u - .12 * v + 1;
        return new double[] {(173 * u + 23 * v + 150) / w, (31 * u + 294 * v + 47) / w};
    }
    private static void near(double a, double b) { require(Math.abs(a - b) < 1e-8, a + " != " + b); }
    private static void require(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}
