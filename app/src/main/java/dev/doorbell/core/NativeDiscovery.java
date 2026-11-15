package dev.doorbell.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.w3c.dom.Document;

/** Local TCP-camera discovery. Does not implement the UDP media/P2P transport. */
public final class NativeDiscovery {
    private static final int[] KEY = {0x1f2d3c4b,0x5a6c7f8d,0x38172e4b,0x8271635a,
        0x863f1a2b,0xa5c6f7d8,0x8371e1b4,0x17f2d3a5};
    private NativeDiscovery() {}
    public static byte[] crypt(byte[] input, int transaction) {
        byte[] result=input.clone();
        for(int i=0;i<result.length;i++) result[i]^=(byte)((KEY[(i/4)%8]+transaction)>>>((i%4)*8));
        return result;
    }
    public static int checksum(byte[] input) {
        int crc=0;
        for(byte b:input) {
            crc^=b&255;
            for(int bit=0;bit<8;bit++) crc=(crc>>>1)^((crc&1)!=0?0xedb88320:0);
        }
        return crc;
    }
    /** Returns the requested reply port; rejects malformed/non-search messages. */
    public static int searchPort(byte[] datagram) throws Exception {
        if(Arrays.equals(datagram,new byte[]{(byte)0xaa,(byte)0xaa,0,0})) return 3000;
        if(datagram.length<20 || datagram.length>4096) throw new IOException("Discovery length");
        ByteBuffer h=ByteBuffer.wrap(datagram).order(ByteOrder.LITTLE_ENDIAN);
        if(h.getInt()!=0x2a87cf3a || h.getInt()!=datagram.length-20 || h.getInt()!=1)
            throw new IOException("Discovery header");
        int transaction=h.getInt(), expected=h.getInt();
        byte[] body=Arrays.copyOfRange(datagram,20,datagram.length);
        if(checksum(body)!=expected) throw new IOException("Discovery checksum");
        Document xml=Xml.parse(new String(crypt(body,transaction),StandardCharsets.UTF_8));
        if(!xml.getDocumentElement().getTagName().equals("P2P") || xml.getElementsByTagName("C2D_S").getLength()!=1)
            throw new IOException("Unsupported discovery operation");
        int port=Integer.parseInt(Xml.text(xml,"port"));
        if(port<1 || port>65535) throw new IOException("Discovery reply port");
        return port;
    }
    /** Experimental legacy layout measured from two local RLN8-410 replies.
     * Unknown descriptor fields are not yet verified against an IPC capture. */
    public static byte[] advertisement(String address, String id, int port) {
        byte[] data=new byte[388];
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(48,0x03020000); b.putInt(52,0x0010000c); b.putInt(56,0x100);
        byte[] digest;
        try { digest=java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        digest[0]=(byte)((digest[0]&0xfc)|2); // Stable locally administered identity, not another device's MAC.
        System.arraycopy(digest,0,data,80,6);
        b.putInt(104,0xaaaa); b.putInt(128,port);
        put(data,108,20,address); put(data,132,32,"Doorbell Lab");
        StringBuilder mac=new StringBuilder();
        for(int i=0;i<6;i++){if(i>0)mac.append(':');mac.append(String.format(java.util.Locale.ROOT,"%02x",digest[i]&255));}
        // The legacy discovery parser expects a 12-hex identity here. An arbitrary
        // model label causes the official CLI to discard the entire advertisement.
        put(data,164,64,mac.toString()); put(data,228,64,id);
        put(data,292,96,mac.toString().replace(":", "").toUpperCase(java.util.Locale.ROOT));
        return data;
    }
    private static void put(byte[] data,int offset,int capacity,String value) {
        byte[] text=value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text,0,data,offset,Math.min(text.length,capacity-1));
    }
}
