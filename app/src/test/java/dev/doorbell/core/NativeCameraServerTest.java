package dev.doorbell.core;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class NativeCameraServerTest {
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static String text(byte[] b){return new String(b,StandardCharsets.UTF_8);}
    private static class Client implements AutoCloseable {
        final Socket socket; byte[] key; int id=0x000400fa;
        Client(int port)throws Exception {socket=new Socket("127.0.0.1",port);socket.setSoTimeout(3000);}
        void send(int cmd,int type,byte[] ext,byte[] body)throws Exception {socket.getOutputStream().write(Baichuan.packet(cmd,id,0,type,ext,body));}
        Baichuan.Packet receive()throws Exception {return Baichuan.read(socket.getInputStream());}
        int login(String password)throws Exception {
            send(1,0x6514,new byte[0],new byte[0]);Baichuan.Packet challenge=receive();
            assertEquals(id,challenge.message());assertEquals(0xdd12,challenge.status());
            String nonce=Xml.text(Xml.parse(text(Baichuan.xor(challenge.body(),id))),"nonce");
            assertEquals(29,nonce.length());
            String login=Xml.body("<LoginUser><userName>"+Baichuan.hash("admin"+nonce)+"</userName><password>"+Baichuan.hash(password+nonce)+"</password></LoginUser>");
            send(1,0x6414,new byte[0],Baichuan.xor(bytes(login),id));
            key=bytes(Baichuan.hash(nonce+"-"+password).substring(0,16));
            Baichuan.Packet result=receive();assertEquals(0,result.type());return result.status();
        }
        void command(int cmd,String body)throws Exception {send(cmd,0x6414,new byte[0],Baichuan.aes(bytes(body),key,true));}
        public void close()throws Exception {socket.close();}
    }
    private DeviceState device(){return new DeviceState("admin","test-only-password","LAB0000000000000001","127.0.0.1");}
    @Test public void acceptsAlternateNvrSignatureDuringLoginAndModernRequests() throws Exception {
        DeviceState d=device();
        try(NativeCameraServer server=new NativeCameraServer(d,0)) {
            server.start();
            try(Client c=new Client(server.port()) {
                @Override Baichuan.Packet receive()throws Exception {
                    Baichuan.Packet packet=super.receive();
                    assertEquals(0x0fedcba0,packet.magic());
                    return packet;
                }
                @Override void send(int cmd,int type,byte[] ext,byte[] body)throws Exception {
                    byte[] packet=Baichuan.packet(cmd,id,0,type,ext,body);
                    ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).putInt(0x0fedcba0);
                    socket.getOutputStream().write(packet);
                }
            }) {
                assertEquals(200,c.login("test-only-password"));
                c.command(80,"");
                assertTrue(text(Baichuan.aes(c.receive().body(),c.key,false)).contains("VersionInfo"));
                c.command(3,preview("mainStream"));assertEquals(200,c.receive().status());
                // A subsequent request acts as a barrier after preview setup.
                c.command(80,"");assertEquals(80,c.receive().command());
                d.ring(true);Baichuan.Packet event=c.receive();assertEquals(33,event.command());
                assertEquals(0,event.message());
                assertEquals("visitor",Xml.text(Xml.parse(text(Baichuan.aes(event.body(),c.key,false))),"status"));
            }
        }
    }
    @Test public void honorsXorOnlyLoginNegotiation() throws Exception {
        try(NativeCameraServer server=new NativeCameraServer(device(),0)) {
            server.start();
            try(Client c=new Client(server.port())) {
                c.socket.getOutputStream().write(Baichuan.packet(1,c.id,0xdc01,0x6514,new byte[0],new byte[0]));
                Baichuan.Packet challenge=c.receive();
                assertEquals(0xdd01,challenge.status());
                String nonce=Xml.text(Xml.parse(text(Baichuan.xor(challenge.body(),c.id))),"nonce");
                String body=Xml.body("<LoginUser><userName>"+Baichuan.hash("admin"+nonce)+"</userName><password>"+Baichuan.hash("test-only-password"+nonce)+"</password></LoginUser>");
                c.send(1,0x6414,new byte[0],Baichuan.xor(bytes(body),c.id));
                assertEquals(200,c.receive().status());
                c.send(80,0x6414,new byte[0],new byte[0]);
                assertTrue(text(Baichuan.xor(c.receive().body(),c.id)).contains("VersionInfo"));
            }
        }
    }
    @Test public void loginVersionAndUnsupportedCommand()throws Exception {
        try(NativeCameraServer server=new NativeCameraServer(device(),0)){server.start();try(Client c=new Client(server.port())){
            assertEquals(200,c.login("test-only-password"));c.command(80,"");Baichuan.Packet p=c.receive();
            assertEquals(c.id,p.message());assertTrue(text(Baichuan.aes(p.body(),c.key,false)).contains("VersionInfo"));
            c.command(199,"");p=c.receive();assertEquals(200,p.status());assertEquals(0,p.type());
            org.w3c.dom.Document support=Xml.parse(text(Baichuan.aes(p.body(),c.key,false)));
            assertEquals("1",Xml.text(support,"channelNum"));
            assertEquals("0",Xml.text(support,"audioTalk"));
            assertEquals("1",Xml.text(support,"noExternStream"));
            assertEquals("1",Xml.text(support,"doorbellVersion"));
            assertEquals("129",Xml.text(support,"eventTypeVersion"));
            assertEquals("0",Xml.text(support,"aitype"));
            assertEquals("smart_bell",Xml.text(Xml.parse(Xml.body(NativeMetadata.deviceInfo(device()))),"type"));
            c.command(58,"");p=c.receive();assertEquals(200,p.status());
            String users=text(Baichuan.aes(p.body(),c.key,false));
            assertEquals("admin",Xml.text(Xml.parse(users),"userName"));
            assertFalse(users.contains("password"));
            c.command(81,"");p=c.receive();assertEquals(200,p.status());
            assertEquals("0",Xml.text(Xml.parse(text(Baichuan.aes(p.body(),c.key,false))),"enable"));
            c.command(98765,"");assertEquals(400,c.receive().status());
        }}
    }
    @Test public void rejectsWrongPassword()throws Exception {
        try(NativeCameraServer server=new NativeCameraServer(device(),0)){server.start();try(Client c=new Client(server.port())){assertEquals(401,c.login("wrong"));}}
    }
    @Test public void rejectsUnauthenticatedCommands()throws Exception {
        try(NativeCameraServer server=new NativeCameraServer(device(),0)){server.start();try(Client c=new Client(server.port())){c.send(3,0x6414,new byte[0],new byte[0]);assertEquals(401,c.receive().status());}}
    }
    @Test public void encryptedExtensionAndRingEvent()throws Exception {
        DeviceState d=device();try(NativeCameraServer server=new NativeCameraServer(d,0)){server.start();try(Client c=new Client(server.port())){
            assertEquals(200,c.login("test-only-password"));c.send(31,0x6414,Baichuan.aes(bytes("<Extension><channelId>0</channelId></Extension>"),c.key,true),new byte[0]);assertEquals(200,c.receive().status());
            assertTrue(text(Baichuan.aes(c.receive().body(),c.key,false)).contains("<status>none</status>"));
            d.ring(true);Baichuan.Packet event=c.receive();assertEquals(33,event.command());assertTrue(text(Baichuan.aes(event.body(),c.key,false)).contains("<status>visitor</status>"));
            d.ring(false);assertTrue(text(Baichuan.aes(c.receive().body(),c.key,false)).contains("<status>none</status>"));
        }}
    }
    @Test public void alternateAlarmSubscriptionDeliversVisitorAndRelease()throws Exception {
        DeviceState d=device();
        try(NativeCameraServer server=new NativeCameraServer(d,0)) {
            server.start();
            try(Client c=new Client(server.port())) {
                assertEquals(200,c.login("test-only-password"));
                c.command(192,"");assertEquals(192,c.receive().command());
                assertEquals(33,c.receive().command());
                d.ring(true);Baichuan.Packet p=c.receive();assertEquals(33,p.command());
                org.w3c.dom.Document event=Xml.parse(text(Baichuan.aes(p.body(),c.key,false)));
                assertEquals("visitor",Xml.text(event,"status"));
                assertTrue(Long.parseLong(Xml.text(event,"timeStamp"))>0);
                d.ring(false);assertEquals("none",Xml.text(Xml.parse(text(Baichuan.aes(c.receive().body(),c.key,false))),"status"));
            }
        }
    }
    @Test public void streamsBcMediaAfterPreview()throws Exception {
        DeviceState d=device();try(NativeCameraServer server=new NativeCameraServer(d,0)){server.start();try(Client c=new Client(server.port())){
            assertEquals(200,c.login("test-only-password"));c.command(3,Xml.body("<Preview><channelId>0</channelId><streamType>mainStream</streamType></Preview>"));
            Baichuan.Packet info=c.receive();assertEquals(0,info.type());assertEquals("1001",text(Arrays.copyOf(info.body(),4)));assertTrue(text(Baichuan.aes(info.extension(),c.key,false)).contains("binaryData"));
            long until=System.nanoTime()+2_000_000_000L;while(d.media.clients()==0 && System.nanoTime()<until)Thread.sleep(10);assertEquals(1,d.media.clients());
            byte[] nal={0,0,0,1,0x65,1,2,3,4};d.media.publish(new MediaHub.Frame(nal,123456,true,false));
            Baichuan.Packet video=c.receive();assertEquals("00dc",text(Arrays.copyOf(video.body(),4)));assertArrayEquals(nal,Arrays.copyOfRange(video.body(),24,33));
            c.command(4,"");assertEquals(200,c.receive().status());
        }}
    }
    @Test public void wireFormatAndFragmentedInput()throws Exception {
        byte[] p=Baichuan.packet(80,0x123456fa,200,0x6414,new byte[]{1,2},new byte[]{3,4});
        assertArrayEquals(new byte[]{(byte)0xf0,(byte)0xde,(byte)0xbc,10,80,0,0,0,4,0,0,0,(byte)0xfa,0x56,0x34,0x12,(byte)200,0,0x14,0x64,2,0,0,0},Arrays.copyOf(p,24));
        InputStream fragmented=new FilterInputStream(new ByteArrayInputStream(p)){public int read(byte[] b,int off,int len)throws IOException{return super.read(b,off,Math.min(1,len));}};
        assertArrayEquals(new byte[]{3,4},Baichuan.read(fragmented).body());
    }
    @Test(expected=IOException.class) public void rejectsOversizedFrame()throws Exception {
        byte[] p=Baichuan.packet(1,0,0,0x6414,new byte[0],new byte[0]);ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(8,Integer.MAX_VALUE);Baichuan.read(new ByteArrayInputStream(p));
    }
    @Test(expected=IOException.class) public void rejectsInvalidOffset()throws Exception {
        byte[] p=Baichuan.packet(1,0,0,0x6414,new byte[0],new byte[0]);ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(20,1);Baichuan.read(new ByteArrayInputStream(p));
    }
    @Test(expected=IOException.class) public void rejectsExternalEntities()throws Exception {Xml.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>");}
    @Test public void slowViewerResynchronizesOnKeyframe()throws Exception {
        MediaHub media=new MediaHub();try(MediaHub.Subscription sub=media.subscribe()){
            MediaHub.Frame key=new MediaHub.Frame(new byte[]{1},0,true,false), delta=new MediaHub.Frame(new byte[]{2},1,false,false);
            media.publish(key);for(int i=0;i<100;i++)media.publish(delta);assertEquals(1,media.droppedFrames.get());
            media.publish(key);assertTrue(sub.poll().key());
        }
    }
    @Test public void aacHeaderAndPadding(){
        byte[] frame=Baichuan.audio(new MediaHub.Frame(new byte[100],0,false,true));assertEquals(120,frame.length);assertEquals("05wb",text(Arrays.copyOf(frame,4)));assertEquals(107,frame[4]&255);assertEquals(255,frame[8]&255);
    }
    private static String preview(String stream) { return Xml.body("<Preview><channelId>0</channelId><streamType>"+stream+"</streamType></Preview>"); }
    private static String status(Client c)throws Exception { return Xml.text(Xml.parse(text(Baichuan.aes(c.receive().body(),c.key,false))),"status"); }
    @Test public void motionAndVisitorDoNotClearEachOther()throws Exception {
        DeviceState d=device(); d.motion(true);
        try(NativeCameraServer server=new NativeCameraServer(d,0)){server.start();try(Client c=new Client(server.port())){
            assertEquals(200,c.login("test-only-password"));c.command(31,"");assertEquals(200,c.receive().status());
            assertEquals("MD",status(c));d.ring(true);assertEquals("MD,visitor",status(c));
            d.motion(false);assertEquals("visitor",status(c));d.ring(false);assertEquals("none",status(c));
        }}
    }
    @Test public void simultaneousStreamsStopIndependentlyAndRestart()throws Exception {
        DeviceState d=device();d.subMedia.width=640;d.subMedia.height=360;
        try(NativeCameraServer server=new NativeCameraServer(d,0)){server.start();try(Client c=new Client(server.port())){
            assertEquals(200,c.login("test-only-password"));
            c.id=0x100fa;c.command(3,preview("mainStream"));assertEquals(200,c.receive().status());
            c.id=0x200fa;c.command(3,preview("subStream"));Baichuan.Packet info=c.receive();assertEquals(200,info.status());
            assertEquals(1,d.media.clients());assertEquals(1,d.subMedia.clients());
            d.subMedia.publish(new MediaHub.Frame(new byte[]{0,0,0,1,0x65,7},1,true,false));
            assertEquals(0x200fa,info.message());
            assertEquals(640,ByteBuffer.wrap(info.body()).order(ByteOrder.LITTLE_ENDIAN).getInt(8));
            assertEquals(0x200fa,c.receive().message());
            c.command(4,preview("subStream"));assertEquals(200,c.receive().status());
            assertEquals(1,d.media.clients());assertEquals(0,d.subMedia.clients());
            c.id=0x300fa;c.command(3,preview("subStream"));assertEquals(200,c.receive().status());
            d.subMedia.publish(new MediaHub.Frame(new byte[]{0,0,0,1,0x65,9},2,true,false));
            assertEquals(0x300fa,c.receive().message());
            c.command(4,"");assertEquals(200,c.receive().status());assertEquals(0,d.media.clients());assertEquals(0,d.subMedia.clients());
        }}
    }
    @Test public void metadataDescribesActualStreamsAndConfiguredPorts()throws Exception {
        DeviceState d=device();d.media.width=1920;d.subMedia.width=640;d.subMedia.bitrateKbps=500;d.rtspEnabled=true;d.rtspPort=8555;
        try(NativeCameraServer server=new NativeCameraServer(d,0)){server.start();try(Client c=new Client(server.port())){
            assertEquals(200,c.login("test-only-password"));c.command(146,"");String stream=text(Baichuan.aes(c.receive().body(),c.key,false));
            assertTrue(stream.contains("<width>1920</width>"));assertTrue(stream.contains("<width>640</width>"));
            c.command(37,"");String ports=text(Baichuan.aes(c.receive().body(),c.key,false));assertTrue(ports.contains("<rtspPort>8555</rtspPort>"));
            c.command(56,"");String enc=text(Baichuan.aes(c.receive().body(),c.key,false));assertTrue(enc.contains("<bitRate>500</bitRate>"));
            c.command(104,"");assertTrue(text(Baichuan.aes(c.receive().body(),c.key,false)).contains("<timeZone>0</timeZone>"));
        }}
    }
    @Test public void invalidAndDuplicateStreamsFailExplicitly()throws Exception {
        try(NativeCameraServer server=new NativeCameraServer(device(),0)){server.start();try(Client c=new Client(server.port())){
            assertEquals(200,c.login("test-only-password"));c.command(3,preview("externStream"));assertEquals(400,c.receive().status());
            c.command(3,preview("subStream"));assertEquals(200,c.receive().status());
            c.command(3,preview("subStream"));assertEquals(400,c.receive().status());
        }}
    }
    @Test public void blockedSocketCannotBlockEventPublisher()throws Exception {
        java.util.concurrent.CountDownLatch began=new java.util.concurrent.CountDownLatch(1), closed=new java.util.concurrent.CountDownLatch(1);
        Socket blocked=new Socket(){
            public OutputStream getOutputStream(){ return new OutputStream(){ public void write(int ignored)throws IOException {
                began.countDown();try{closed.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}throw new IOException("Closed");
            }}; }
            public void close(){ closed.countDown(); }
        };
        try(SocketWriter writer=new SocketWriter(blocked)){
            writer.offer(new byte[]{1});assertTrue(began.await(1,java.util.concurrent.TimeUnit.SECONDS));
            long start=System.nanoTime();for(int i=0;i<100;i++)writer.offer(new byte[]{2});
            assertTrue("Event callbacks must not wait on socket writes",System.nanoTime()-start<1_000_000_000L);
            assertEquals(0,closed.getCount());
        }
    }

    @Test public void clockSynchronizationIsTransactional()throws Exception {
        String fields="<timeZone>28800</timeZone><osdFormat>YMD</osdFormat><timeFormat>0</timeFormat><year>2030</year><month>2</month><day>3</day><hour>4</hour><minute>5</minute><second>6</second>";
        DeviceState d=device();
        try(NativeCameraServer server=new NativeCameraServer(d,0)){server.start();try(Client c=new Client(server.port())){
            assertEquals(200,c.login("test-only-password"));
            c.command(105,"\n  "+Xml.body("<SystemGeneral>"+fields+"</SystemGeneral>"));assertEquals(200,c.receive().status());
            c.command(104,"");String time=text(Baichuan.aes(c.receive().body(),c.key,false));assertTrue(time.contains("<year>2030</year>"));assertTrue(time.contains("<hour>4</hour>"));
            c.command(105,Xml.body("<SystemGeneral>"+fields.replace("<month>2", "<month>99")+"</SystemGeneral>"));assertEquals(400,c.receive().status());
            c.command(104,"");assertTrue(text(Baichuan.aes(c.receive().body(),c.key,false)).contains("<month>2</month>"));
        }}
    }

}
