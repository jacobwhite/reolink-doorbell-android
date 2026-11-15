package dev.doorbell.core;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class DeviceState {
    public final String username, password, id;
    public final MediaHub media = new MediaHub();
    public final MediaHub subMedia = new MediaHub();
    final NativeClock clock = new NativeClock();
    public volatile int webPort = 4444, rtspPort = 8554, onvifPort = 8000;
    public volatile boolean tlsEnabled = true, rtspEnabled, onvifEnabled, audioEnabled;
    public record Alarm(boolean motion, boolean visitor) {
        public String status() { return motion ? (visitor ? "MD,visitor" : "MD") : (visitor ? "visitor" : "none"); }
    }
    private boolean motion, visitor;
    public final List<Consumer<Alarm>> alarmListeners = new CopyOnWriteArrayList<>();
    public volatile String address;
    public volatile boolean running;
    public final List<Consumer<Boolean>> ringListeners = new CopyOnWriteArrayList<>();
    public Consumer<String> logSink = message -> {};
    private final Deque<String> logs = new ArrayDeque<>();
    public DeviceState(String username, String password, String id, String address) {
        this.username=username; this.password=password; this.id=id; this.address=address;
    }
    public synchronized void log(String message) { if (logs.size() == 80) logs.removeFirst(); logs.addLast(Instant.now()+"  "+message); logSink.accept(message); }
    public synchronized String logs() { return String.join("\n", logs); }
    public synchronized Alarm alarm() { return new Alarm(motion, visitor); }
    public synchronized void motion(boolean active) {
        if (motion == active) return;
        motion = active; log(active ? "Motion detected" : "Motion cleared");
        for (Consumer<Alarm> listener : alarmListeners) listener.accept(alarm());
    }
    public synchronized void ring(boolean active) {
        visitor = active; log(active ? "Doorbell pressed" : "Doorbell released");
        for (Consumer<Boolean> listener:ringListeners) listener.accept(active);
        for (Consumer<Alarm> listener : alarmListeners) listener.accept(alarm());
    }
}
