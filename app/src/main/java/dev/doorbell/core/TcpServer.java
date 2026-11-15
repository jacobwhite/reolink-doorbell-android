package dev.doorbell.core;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public abstract class TcpServer implements AutoCloseable {
    protected final DeviceState state;
    private final ServerSocket server;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newFixedThreadPool(12);
    private final Semaphore limit = new Semaphore(12);
    protected volatile boolean closed;
    public TcpServer(DeviceState state, int port) throws IOException {
        this(state, port, null);
    }
    public TcpServer(DeviceState state, int port, InetAddress address) throws IOException {
        this.state=state; server=new ServerSocket(); server.setReuseAddress(true); server.bind(new InetSocketAddress(address,port));
    }
    public int port() { return server.getLocalPort(); }
    public void start() {
        Thread accept = new Thread(() -> {
            while (!closed) try {
                Socket s=server.accept();
                if (!limit.tryAcquire()) { s.close(); continue; }
                s.setSoTimeout(65000); s.setTcpNoDelay(true); sockets.add(s);
                workers.execute(() -> { try(s) { handle(s); } catch(Exception e) {
                    if(!closed) {
                        String detail=e.getMessage();
                        // Only the fixed framing diagnostic is safe to expose; never raw XML/auth data.
                        state.log(getClass().getSimpleName()+": "+e.getClass().getSimpleName()+
                            (detail!=null && detail.startsWith("Bad Baichuan magic:") ? " "+detail : ""));
                    }
                } finally { sockets.remove(s); limit.release(); } });
            } catch(IOException e) { if(!closed) state.log("Listener failed: "+e.getMessage()); }
        },getClass().getSimpleName()); accept.setDaemon(true); accept.start();
    }
    protected abstract void handle(Socket socket) throws Exception;
    public void close() { closed=true; try{server.close();}catch(IOException ignored){} for(Socket s:sockets)try{s.close();}catch(IOException ignored){} workers.shutdownNow(); }
    public static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        for(int c; (c=in.read())!=-1;) { if(c=='\n') return new String(b.toByteArray(),StandardCharsetsHolder.UTF8).replace("\r",""); if(b.size()>=8192)throw new IOException("Header too long"); b.write(c); }
        if(b.size()==0) return null; throw new EOFException();
    }
    private static class StandardCharsetsHolder { static final java.nio.charset.Charset UTF8=java.nio.charset.StandardCharsets.UTF_8; }
    public static Map<String,String> headers(InputStream in) throws IOException {
        Map<String,String> out=new HashMap<>();
        for(int count=0;count<80;count++) { String l=line(in); if(l==null)throw new EOFException(); if(l.isEmpty())return out; int n=l.indexOf(':'); if(n>0)out.put(l.substring(0,n).toLowerCase(Locale.ROOT),l.substring(n+1).trim()); }
        throw new IOException("Too many headers");
    }
}
