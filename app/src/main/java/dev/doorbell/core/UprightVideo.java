package dev.doorbell.core;

/** Pixel transforms for a stable landscape stream; no rotation metadata is required. */
public final class UprightVideo {
    private UprightVideo() {}
    public static int rotation(int sensor, int device, boolean front) {
        return Math.floorMod(sensor + (front ? -device : device), 360);
    }
    public static void cropYuv(byte[] src, int sw, int sh, byte[] dst, int dw, int dh,
                              int rotation, boolean mirror, boolean semiPlanar) {
        boolean swap = rotation == 90 || rotation == 270;
        int rw = swap ? sh : sw, rh = swap ? sw : sh;
        int cropW = Math.min(rw, rh * dw / dh) / 2 * 2;
        int cropH = Math.min(rh, rw * dh / dw) / 2 * 2;
        int ox = (rw-cropW)/4*2, oy = (rh-cropH)/4*2;
        for (int plane=0; plane<3; plane++) {
            int div=plane==0?1:2;
            int psw=sw/div, psh=sh/div, pdw=dw/div, pdh=dh/div;
            int pcw=cropW/div, pch=cropH/div;
            int soff=plane==0?0:sw*sh+(semiPlanar?plane-1:(plane-1)*sw*sh/4);
            int doff=plane==0?0:dw*dh+(semiPlanar?plane-1:(plane-1)*dw*dh/4);
            int step=plane>0&&semiPlanar?2:1;
            for (int y=0; y<pdh; y++) for (int x=0; x<pdw; x++) {
                int rx=ox/div+x*pcw/pdw, ry=oy/div+y*pch/pdh, sx,sy;
                switch(rotation) {
                    case 90: sx=ry; sy=psh-1-rx; break;
                    case 180: sx=psw-1-rx; sy=psh-1-ry; break;
                    case 270: sx=psw-1-ry; sy=rx; break;
                    default: sx=rx; sy=ry;
                }
                if(mirror) sx=psw-1-sx;
                dst[doff+(y*pdw+x)*step]=src[soff+(sy*psw+sx)*step];
            }
        }
    }
}
