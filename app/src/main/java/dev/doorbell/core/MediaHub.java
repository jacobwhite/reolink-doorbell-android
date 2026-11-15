package dev.doorbell.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Encoded access units shared by independent, bounded client queues. */
public final class MediaHub {
    public record Frame(byte[] data, long micros, boolean key, boolean audio) {}
    public volatile byte[] sps, pps;
    public volatile int width = 1280, height = 720, fps = 15, bitrateKbps = 2048;
    public final AtomicLong videoFrames = new AtomicLong(), droppedFrames = new AtomicLong();
    private final Set<Subscription> subscribers = ConcurrentHashMap.newKeySet();
    public Runnable requestKeyframe = () -> {};
    public final class Subscription implements AutoCloseable {
        private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(90);
        private boolean waitingForKey = true;
        private boolean closed;
        public Frame poll() throws InterruptedException { return queue.poll(1, TimeUnit.SECONDS); }
        synchronized void offer(Frame frame) {
            if (closed) return;
            if (waitingForKey && (frame.audio || !frame.key)) return;
            if (frame.key) waitingForKey = false;
            if (!queue.offer(frame)) {
                queue.clear(); waitingForKey = true; droppedFrames.incrementAndGet();
                requestKeyframe.run();
            }
        }
        synchronized void resync() { queue.clear(); waitingForKey = true; }
        public synchronized void close() { closed = true; subscribers.remove(this); queue.clear(); }
    }
    public Subscription subscribe() { Subscription s = new Subscription(); subscribers.add(s); requestKeyframe.run(); return s; }
    public int clients() { return subscribers.size(); }
    public void resync() { for (Subscription s : subscribers) s.resync(); requestKeyframe.run(); }
    public void publish(Frame frame) { if (!frame.audio) videoFrames.incrementAndGet(); for (Subscription s : subscribers) s.offer(frame); }
    public static List<byte[]> nals(byte[] bytes) {
        List<byte[]> result = new ArrayList<>(); int start = -1;
        for (int i = 0; i < bytes.length - 2;) {
            int size = bytes[i] == 0 && bytes[i+1] == 0 ? (bytes[i+2] == 1 ? 3 : (i+3 < bytes.length && bytes[i+2] == 0 && bytes[i+3] == 1 ? 4 : 0)) : 0;
            if (size > 0) { if (start >= 0 && i > start) result.add(Arrays.copyOfRange(bytes, start, i)); start = i + size; i += size; } else i++;
        }
        if (start >= 0 && start < bytes.length) result.add(Arrays.copyOfRange(bytes, start, bytes.length));
        if (start < 0 && bytes.length > 0) result.add(bytes);
        return result;
    }
    public byte[] withParameters(byte[] data) {
        if (sps == null || pps == null) return data;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] n : new byte[][]{sps, pps, data}) { if(n != data) out.write(new byte[]{0,0,0,1},0,4); out.write(n,0,n.length); }
        return out.toByteArray();
    }
}
