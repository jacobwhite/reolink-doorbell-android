package dev.doorbell.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class NativeDiscoveryTest {
    // Generated independently with reolink_aio 0.23.1 UDP cipher/CRC, transaction 7.
    private byte[] search() {
        String hex="3acf872a3500000001000000070000000ede05366e6c1f4faa432f68167144065d171ebc0e6a50f4abc9f29689d041bf83a39d6526021130e01052667d6d257c3e304fbe1d4a0dd6e1";
        byte[] b=new byte[hex.length()/2];
        for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(hex.substring(i*2,i*2+2),16);
        return b;
    }
    @Test public void acceptsIndependentSearchAndLegacyProbe() throws Exception {
        assertEquals(43210,NativeDiscovery.searchPort(search()));
        assertEquals(3000,NativeDiscovery.searchPort(new byte[]{(byte)0xaa,(byte)0xaa,0,0}));
    }
    @Test public void rejectsCorruptionAndTruncation() {
        byte[] b=search();b[b.length-1]^=1;
        assertThrows(Exception.class,()->NativeDiscovery.searchPort(b));
        assertThrows(Exception.class,()->NativeDiscovery.searchPort(new byte[19]));
        byte[] huge=search();huge[4]=(byte)0xff;
        assertThrows(Exception.class,()->NativeDiscovery.searchPort(huge));
    }
    @Test public void advertisementUsesOwnAddressAndStableLocalIdentity() {
        byte[] b=NativeDiscovery.advertisement("192.0.2.20","LAB-TEST-123",9000);
        assertEquals(388,b.length);
        assertEquals(9000,ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(128));
        assertEquals("192.0.2.20",new String(b,108,20,StandardCharsets.US_ASCII).trim());
        assertEquals("LAB-TEST-123",new String(b,228,64,StandardCharsets.US_ASCII).trim());
        assertEquals(2,b[80]&3);
        String mac=new String(b,164,64,StandardCharsets.US_ASCII).trim();
        String identity=new String(b,292,96,StandardCharsets.US_ASCII).trim();
        assertTrue(identity.matches("[0-9A-F]{12}"));
        assertEquals(mac.replace(":", "").toUpperCase(java.util.Locale.ROOT),identity);
        assertArrayEquals(b,NativeDiscovery.advertisement("192.0.2.20","LAB-TEST-123",9000));
    }
}
