package dev.doorbell.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.w3c.dom.Document;

/** Experimental camera-side Baichuan TCP server. Unsupported commands fail explicitly. */
public final class NativeCameraServer extends TcpServer {
    public Runnable onViewerConnected=()->{}, onViewerDisconnected=()->{};
    public NativeCameraServer(DeviceState state,int port)throws IOException { super(state,port); }
    public NativeCameraServer(DeviceState state,int port,InetAddress address)throws IOException { super(state,port,address); }
    @Override protected void handle(Socket s)throws Exception { new Session(s).run(); }
    private final class Session {
        Socket socket;
        final SocketWriter writer;
        // Match the 29-character nonce used by hardware, leaving room for a
        // terminating NUL in clients that use a 32-byte nonce buffer.
        final String nonce=UUID.randomUUID().toString().substring(0,29);
        byte[] key;
        boolean authenticated, challenged;
        volatile boolean aesMode=true;
        volatile boolean subscribed;
        final Map<String,Playback> playbacks = new ConcurrentHashMap<>();
        int eventId;
        volatile int wireMagic=0x0abcdef0;
        final Consumer<DeviceState.Alarm> event = this::alarmEvent;
        void alarmEvent(DeviceState.Alarm alarm) {
            if (!subscribed) return;
            try {
                writer.offer(response(33,eventId,200,alarmXml(alarm),false));
                state.log("Native alarm queued to "+socket.getInetAddress().getHostAddress()+": "+alarm.status());
            }
            catch (Exception e) { writer.close(); }
        }
        Session(Socket s)throws IOException { socket=s; writer=new SocketWriter(s); }
        String alarmXml(DeviceState.Alarm alarm) {
            return Xml.body("<AlarmEventList version=\"1.1\"><AlarmEvent version=\"1.1\"><channelId>0</channelId><status>" +
                alarm.status()+"</status><AItype>none</AItype><recording>0</recording><timeStamp>"+
                java.time.Instant.now().getEpochSecond()+"</timeStamp></AlarmEvent></AlarmEventList>");
        }
        void run()throws Exception {
            state.log("Native client connected from "+socket.getInetAddress().getHostAddress()); state.alarmListeners.add(event);
            try {
                while(!closed && !socket.isClosed()) {
                    Baichuan.Packet p=Baichuan.read(socket.getInputStream());
                    wireMagic=p.magic();
                    state.log("Native packet from "+socket.getInetAddress().getHostAddress()+": command="+p.command()+", class="+Integer.toHexString(p.type())+", status="+Integer.toHexString(p.status())+", message="+Integer.toHexString(p.message())+", authenticated="+authenticated);
                    if(p.command()==1) {login(p);continue;}
                    if(!authenticated){reply(p.command(),p.message(),401,"",true);continue;}
                    // Validate encrypted XML independently of command dispatch. No raw credentials or XML are logged.
                    String body=decrypt(p.body(),p.message());
                    if(p.extension().length>0) {
                        Document extension=Xml.parse(decrypt(p.extension(),p.message()));
                        String channel=Xml.text(extension,"channelId");
                        if(!channel.isEmpty() && !channel.equals("0")) {reply(p.command(),p.message(),400,"",false);continue;}
                    }
                    if(!body.isEmpty()) Xml.parse(body);
                    state.log("Native command "+p.command());
                    switch(p.command()) {
                        case 2 -> {reply(2,p.message(),200,"",false);return;}
                        case 3 -> startVideo(p, body);
                        case 4 -> { stopVideo(body); reply(4,p.message(),200,"",false); }
                        case 31,192 -> {
                            eventId=p.message();subscribed=true;reply(p.command(),p.message(),200,"",false);
                            state.log("Native alarms subscribed by "+socket.getInetAddress().getHostAddress()+" via "+p.command());
                            event.accept(state.alarm());
                        }
                        case 37 -> reply(37,p.message(),200,Xml.body(NativeMetadata.ports(state,port())),false);
                        case 44,110 -> reply(p.command(),p.message(),200,Xml.body(NativeMetadata.osd()),false);
                        case 45 -> reply(45,p.message(),"Doorbell".equals(Xml.text(Xml.parse(body),"name"))?200:400,"",false);
                        case 56 -> reply(56,p.message(),200,Xml.body(NativeMetadata.encoding(state)),false);
                        case 58 -> reply(58,p.message(),200,Xml.body("<UserList version=\"1.1\"><User><userName>"+Xml.escape(state.username)+"</userName><userLevel>1</userLevel></User></UserList>"),false);
                        case 81 -> reply(81,p.message(),200,Xml.body("<Record version=\"1.1\"><channelId>0</channelId><enable>0</enable></Record>"),false);
                        case 104 -> reply(104,p.message(),200,Xml.body(state.clock.xml()),false);
                        case 105 -> reply(105,p.message(),state.clock.set(Xml.parse(body))?200:400,"",false);
                        case 80 -> reply(80,p.message(),200,Xml.body("<VersionInfo version=\"1.1\"><name>Doorbell Lab</name><type>Reolink Video Doorbell WiFi</type><serialNumber>"+state.id+"</serialNumber><buildDay>2026-11-14</buildDay><hardwareVersion>ANDROID_EMULATOR</hardwareVersion><cfgVersion>v0.2.0</cfgVersion><firmwareVersion>v0.2.0.experimental</firmwareVersion><detail>Doorbell Lab Android emulator</detail></VersionInfo>"),false);
                        case 93 -> reply(93,p.message(),200,Xml.body("<LinkType version=\"1.1\"><type>WIFI</type></LinkType>"),false);
                        case 114 -> reply(114,p.message(),200,Xml.body("<P2p version=\"1.1\"><uid>"+state.id+"</uid></P2p>"),false);
                        case 151 -> reply(151,p.message(),200,Xml.body(NativeMetadata.abilities(state)),false);
                        case 199 -> reply(199,p.message(),200,Xml.body(NativeMetadata.support(state)),false);
                        case 146 -> reply(146,p.message(),200,Xml.body(NativeMetadata.streamInfo(state)),false);
                        default -> {state.log("UNSUPPORTED native command "+p.command());reply(p.command(),p.message(),400,"",false);}
                    }
                }
            } finally { subscribed=false; stopVideo(""); state.alarmListeners.remove(event); writer.close(); state.log("Native client disconnected"); }
        }
        void login(Baichuan.Packet p)throws Exception {
            if(p.type()==0x6514) {
                if(challenged || authenticated)throw new IOException("Repeated challenge");
                challenged=true;
                aesMode=p.status()!=0xdc01;
                String challenge=Xml.body("<Encryption version=\"1.1\"><type>md5</type><nonce>"+nonce+"</nonce></Encryption>");
                // Modern-upgrade response: 20-byte 0x6614 header, XOR encryption with AES negotiation.
                send(Baichuan.packet(1,p.message(),aesMode?0xdd12:0xdd01,0x6614,new byte[0],Baichuan.xor(bytes(challenge),p.message()),wireMagic));return;
            }
            if(!challenged || authenticated) {reply(1,p.message(),401,"",true);throw new IOException("Login without challenge");}
            Document d=Xml.parse(new String(Baichuan.xor(p.body(),p.message()),StandardCharsets.UTF_8));
            state.log("Native login transport type="+Xml.text(d,"type")+", udpPort="+Xml.text(d,"udpPort"));
            boolean valid=equal(Xml.text(d,"userName"),Baichuan.hash(state.username+nonce)) & equal(Xml.text(d,"password"),Baichuan.hash(state.password+nonce));
            if(!valid) {reply(1,p.message(),401,"",true);throw new IOException("Native login rejected");}
            key=bytes(Baichuan.hash(nonce+"-"+state.password).substring(0,16));authenticated=true;
            reply(1,p.message(),200,Xml.body(NativeMetadata.deviceInfo(state)+NativeMetadata.streamInfo(state)),true);
            state.log("Native authentication accepted; NVR compatibility still unverified");
        }
        String decrypt(byte[] b,int id)throws Exception {
            if(b.length==0)return "";
            String s=new String(Baichuan.aes(b,key,false),StandardCharsets.UTF_8).trim();
            if(s.startsWith("<?xml") || s.startsWith("<body") || s.startsWith("<Extension")){aesMode=true;return s;}
            s=new String(Baichuan.xor(b,id),StandardCharsets.UTF_8);aesMode=false;return s;
        }
        String streamName(String body) throws Exception {
            if (body.isEmpty()) return "mainStream";
            Document document=Xml.parse(body);
            String channel=Xml.text(document,"channelId");
            if (!channel.isEmpty() && !channel.equals("0")) return "invalid";
            String name=Xml.text(document,"streamType");
            return name.isEmpty() ? "mainStream" : name;
        }
        void startVideo(Baichuan.Packet p, String body)throws Exception {
            String name=streamName(body);
            Document preview=body.isEmpty()?null:Xml.parse(body);
            state.log("Native preview: stream="+name+", channel="+(preview==null?"":Xml.text(preview,"channelId"))+", handle="+(preview==null?"":Xml.text(preview,"handle")));
            if ((!name.equals("mainStream") && !name.equals("subStream")) || playbacks.containsKey(name)) {
                reply(3,p.message(),400,"",false);return;
            }
            MediaHub hub=name.equals("subStream") ? state.subMedia : state.media;
            Playback playback=new Playback(p,name,hub);
            playbacks.put(name,playback);
            try { playback.emit(Baichuan.info(hub)); playback.start(); }
            catch(Exception e) { playback.close(); throw e; }
            // Experimental fallback for the observed NVR transport: it starts
            // preview but never issues 31/192. Send transitions on that session.
            if(!subscribed && wireMagic==0x0fedcba0) {
                eventId=0;subscribed=true;
                state.log("Native NVR preview alarm fallback enabled for "+socket.getInetAddress().getHostAddress());
            }
        }
        void stopVideo(String body)throws Exception {
            if (body.isEmpty()) { for(Playback playback:playbacks.values()) playback.close(); }
            else { Playback playback=playbacks.get(streamName(body)); if(playback!=null)playback.close(); }
        }
        final class Playback implements AutoCloseable {
            final Baichuan.Packet request;
            final String name;
            final MediaHub hub;
            final MediaHub.Subscription frames;
            final AtomicBoolean active=new AtomicBoolean(true);
            boolean announced;
            boolean binaryAnnounced;
            Thread sender;
            Playback(Baichuan.Packet request,String name,MediaHub hub) {
                this.request=request;this.name=name;this.hub=hub;frames=hub.subscribe();
            }
            void start() {
                announced=true; onViewerConnected.run();
                sender=new Thread(this::stream,"NativeVideo-"+name);sender.setDaemon(true);sender.start();
            }
            void stream() {
                try {
                    int width=hub.width,height=hub.height,fps=hub.fps;
                    while(active.get() && !closed && !socket.isClosed()) {
                        MediaHub.Frame f=frames.poll();if(f==null)continue;
                        if(width!=hub.width || height!=hub.height || fps!=hub.fps) {
                            emit(Baichuan.info(hub));width=hub.width;height=hub.height;fps=hub.fps;
                        }
                        byte[] data=f.audio()?Baichuan.audio(f):Baichuan.video(f);
                        for(int offset=0;offset<data.length && active.get();offset+=32000)
                            emit(Arrays.copyOfRange(data,offset,Math.min(offset+32000,data.length)));
                    }
                } catch(Exception e) {
                    if(active.get()) { state.log("Native stream ended: "+e.getClass().getSimpleName());writer.close(); }
                } finally { close(); }
            }
            synchronized void emit(byte[] data)throws Exception {
                if(active.get()) { binary(request,data,!binaryAnnounced); binaryAnnounced=true; }
            }
            public synchronized void close() {
                if (!active.getAndSet(false)) return;
                frames.close();playbacks.remove(name,this);
                if(sender!=null)sender.interrupt();
                if(announced)onViewerDisconnected.run();
            }
        }
        void binary(Baichuan.Packet p,byte[] payload,boolean announce)throws Exception {
            byte[] ext=announce?bytes("<?xml version=\"1.0\"?><Extension version=\"1.1\"><binaryData>1</binaryData></Extension>"):new byte[0];
            ext=aesMode?Baichuan.aes(ext,key,true):Baichuan.xor(ext,p.message());
            writer.send(Baichuan.packet(3,p.message(),200,0,ext,payload,wireMagic));
        }
        byte[] response(int cmd,int id,int status,String body,boolean xor)throws Exception {
            byte[] b=bytes(body);if(b.length>0)b=xor||!aesMode?Baichuan.xor(b,id):Baichuan.aes(b,key,true);
            return Baichuan.packet(cmd,id,status,0,new byte[0],b,wireMagic);
        }
        void reply(int cmd,int id,int status,String body,boolean xor)throws Exception {
            writer.send(response(cmd,id,status,body,xor));
        }
        void send(byte[] data)throws IOException {writer.send(data);}
    }
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static boolean equal(String a,String b){return MessageDigest.isEqual(bytes(a),bytes(b));}
}
