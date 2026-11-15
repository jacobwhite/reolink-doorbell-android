package dev.doorbell.core;

import java.io.IOException;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.*;

/** Single ordered writer: camera/event callbacks never wait for a slow network peer. */
final class SocketWriter implements AutoCloseable {
    private record Write(byte[] bytes, CompletableFuture<Void> done) {}
    private static final Set<SocketWriter> ACTIVE = ConcurrentHashMap.newKeySet();
    static {
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NativeWriteWatchdog"); t.setDaemon(true); return t;
        });
        watchdog.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            for (SocketWriter writer : ACTIVE) {
                long began = writer.writeStarted;
                if (began != 0 && now - began > TimeUnit.SECONDS.toNanos(5)) writer.close();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
    private final ArrayBlockingQueue<Write> queue = new ArrayBlockingQueue<>(64);
    private final Socket socket;
    private final Thread thread;
    private volatile boolean closed;
    private volatile long writeStarted;

    SocketWriter(Socket socket) {
        this.socket = socket;
        ACTIVE.add(this);
        thread = new Thread(this::loop, "NativeSocketWriter");
        thread.setDaemon(true); thread.start();
    }
    void send(byte[] bytes) throws IOException {
        try { enqueue(bytes).get(6, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Native write interrupted"); }
        catch (Exception e) { close(); throw new IOException("Native write failed", e); }
    }
    void offer(byte[] bytes) { enqueue(bytes); }
    private CompletableFuture<Void> enqueue(byte[] bytes) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        synchronized (queue) {
            if (closed || !queue.offer(new Write(bytes, done))) {
                done.completeExceptionally(new IOException("Native output queue closed/full"));
                close();
            }
        }
        return done;
    }
    private void loop() {
        try {
            while (!closed) {
                Write write = queue.take();
                try {
                    writeStarted = System.nanoTime();
                    socket.getOutputStream().write(write.bytes());
                    socket.getOutputStream().flush();
                    write.done().complete(null);
                } catch (IOException e) { write.done().completeExceptionally(e); throw e; }
                finally { writeStarted = 0; }
            }
        } catch (Exception ignored) { /* Reader observes closure; do not log payloads. */ }
        finally { close(); }
    }
    public void close() {
        synchronized (queue) {
            if (closed) return;
            closed = true;
            try { socket.close(); } catch (IOException ignored) {}
            for (Write write; (write = queue.poll()) != null;) write.done().completeExceptionally(new IOException("Closed"));
        }
        ACTIVE.remove(this);
        thread.interrupt();
    }
}
