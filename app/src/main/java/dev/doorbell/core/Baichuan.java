package dev.doorbell.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Wire primitives, based on published reolink_aio and camera-simulator protocol research. */
public final class Baichuan {
    private static final int[] XOR={0x1f,0x2d,0x3c,0x4b,0x5a,0x69,0x78,0xff};
    public record Packet(int command, int message, int status, int type, byte[] extension, byte[] body, int magic) {}
    public static Packet read(InputStream in) throws IOException {
        byte[] raw=readExactly(in,20); ByteBuffer h=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int magic=h.getInt();
        // Both native BC signatures use the same little-endian header fields.
        // RLN8-410 NVR camera connections use the alternate signature.
        if(magic!=0x0abcdef0 && magic!=0x0fedcba0) {
            String prefix=new String(raw,0,4,StandardCharsets.US_ASCII);
            String protocol=(prefix.equals("POST") || prefix.equals("GET ") || prefix.equals("HEAD") || prefix.equals("OPTI"))
                ? "HTTP/ONVIF request received on native TCP port" : "unrecognized protocol";
            throw new IOException("Bad Baichuan magic: "+protocol+" ("+Integer.toHexString(magic)+")");
        }
        int cmd=h.getInt(),size=h.getInt(),id=h.getInt(),status=Short.toUnsignedInt(h.getShort()),type=Short.toUnsignedInt(h.getShort());
        if(size<0 || size>1024*1024)throw new IOException("Invalid Baichuan length");
        int offset=0;
        if(type==0x6414 || type==0 || type==0x8264)offset=ByteBuffer.wrap(readExactly(in,4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
        else if(type!=0x6514 && type!=0x6614)throw new IOException("Unsupported message class");
        if(offset<0 || offset>size)throw new IOException("Invalid extension length");
        byte[] b=readExactly(in,size);
        return new Packet(cmd,id,status,type,Arrays.copyOfRange(b,0,offset),Arrays.copyOfRange(b,offset,size),magic);
    }
    public static byte[] readExactly(InputStream in,int length)throws IOException { byte[] b=new byte[length];new DataInputStream(in).readFully(b);return b; }
    public static byte[] packet(int cmd,int id,int status,int type,byte[] ext,byte[] body) {
        return packet(cmd,id,status,type,ext,body,0x0abcdef0);
    }
    public static byte[] packet(int cmd,int id,int status,int type,byte[] ext,byte[] body,int magic) {
        boolean modern=type==0x6414 || type==0 || type==0x8264;
        ByteBuffer b=ByteBuffer.allocate((modern?24:20)+ext.length+body.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(magic).putInt(cmd).putInt(ext.length+body.length).putInt(id).putShort((short)status).putShort((short)type);
        if(modern)b.putInt(ext.length);
        b.put(ext).put(body);return b.array();
    }
    public static byte[] xor(byte[] b,int offset) { byte[] out=b.clone();offset&=255;for(int i=0;i<out.length;i++)out[i]^=(byte)(XOR[(i+offset)%8]^offset);return out; }
    public static String hash(String input) {
        try { byte[] b=MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02X",v&255));return s.substring(0,31); }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static byte[] aes(byte[] b,byte[] key,boolean encrypt)throws GeneralSecurityException {
        Cipher c=Cipher.getInstance("AES/CFB/NoPadding");c.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec("0123456789abcdef".getBytes(StandardCharsets.US_ASCII)));return c.doFinal(b);
    }
    public static byte[] info(MediaHub m) { ByteBuffer b=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0x31303031).putInt(32).putInt(m.width).putInt(m.height);b.put(17,(byte)m.fps);return b.array(); }
    public static byte[] video(MediaHub.Frame f) {
        return video(f, System.currentTimeMillis()/1000L);
    }
    public static byte[] video(MediaHub.Frame f, long epochSeconds) {
        // BCMedia I-frame extension: POSIX seconds, as decoded by Neolink.
        int extra=f.key()?4:0;
        int size=f.data().length;ByteBuffer b=ByteBuffer.allocate(24+extra+size+((8-size%8)%8)).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(f.key()?0x63643030:0x63643130).put(new byte[]{'H','2','6','4'}).putInt(size).putInt(extra).putInt((int)f.micros()).putInt(0);
        if(f.key())b.putInt((int)epochSeconds);
        b.put(f.data());return b.array();
    }
    /** AAC-LC mono 44.1kHz: add an ADTS header before BCMedia framing. */
    public static byte[] audio(MediaHub.Frame f) {
        int n=f.data().length+7; if(n>8191)throw new IllegalArgumentException("AAC unit too large");
        ByteBuffer b=ByteBuffer.allocate(8+n+((8-n%8)%8)).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x62773530).putShort((short)n).putShort((short)n);
        b.put(new byte[]{(byte)0xff,(byte)0xf1,0x50,(byte)(0x40|(n>>11)),(byte)(n>>3),(byte)(((n&7)<<5)|31),(byte)0xfc});b.put(f.data());return b.array();
    }
}
