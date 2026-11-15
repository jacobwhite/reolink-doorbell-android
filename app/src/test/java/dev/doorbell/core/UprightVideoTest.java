package dev.doorbell.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class UprightVideoTest {
    @Test public void correctsFrontAndBackSensorMountingInAllOrientations() {
        for(int d=0;d<360;d+=90) {
            assertEquals((270-d+360)%360,UprightVideo.rotation(270,d,true));
            assertEquals((90+d)%360,UprightVideo.rotation(90,d,false));
        }
    }
    @Test public void rotatesPixelsAndPreservesBothChromaLayouts() {
        for(boolean semi:new boolean[]{false,true}) {
            byte[] src=new byte[24]; for(int i=0;i<16;i++)src[i]=(byte)i;
            for(int i=16;i<24;i++)src[i]=(byte)(i+60);
            byte[] dst=new byte[24];
            UprightVideo.cropYuv(src,4,4,dst,4,4,90,false,semi);
            assertEquals(12,dst[0]);assertEquals(0,dst[3]);assertEquals(15,dst[12]);assertEquals(3,dst[15]);
            assertEquals(semi?80:78,dst[16]);
            UprightVideo.cropYuv(src,4,4,dst,4,4,0,true,semi);
            assertEquals(3,dst[0]);assertEquals(0,dst[3]);
        }
    }
    @Test public void portraitCropsToLandscapeWithoutBars() {
        byte[] src=new byte[24]; java.util.Arrays.fill(src,0,16,(byte)100);java.util.Arrays.fill(src,16,24,(byte)90);
        byte[] dst=new byte[48];
        UprightVideo.cropYuv(src,4,4,dst,8,4,90,false,false);
        assertEquals(100,dst[0]);assertEquals(100,dst[2]);assertEquals(100,dst[5]);assertEquals(100,dst[7]);
        assertEquals(90,dst[32]&255);assertEquals(90,dst[33]&255);assertEquals(90,dst[35]&255);
    }
    @Test public void rectangularSourceCropsToFillEveryQuarterTurn() {
        byte[] src=new byte[48];java.util.Arrays.fill(src,0,32,(byte)100);
        for(int rotation:new int[]{0,90,180,270}) {
            byte[] dst=new byte[192];
            UprightVideo.cropYuv(src,8,4,dst,16,8,rotation,false,false);
            for(int y=0;y<8;y++) for(int x=0;x<16;x++) {
                boolean portrait=rotation==90||rotation==270;
                assertEquals(100,dst[y*16+x]&255);
            }
        }
    }
}
