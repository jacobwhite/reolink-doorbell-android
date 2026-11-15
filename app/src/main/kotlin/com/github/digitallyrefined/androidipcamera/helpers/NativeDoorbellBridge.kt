package com.github.digitallyrefined.androidipcamera.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import dev.doorbell.core.*
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Android adapter for the experimental Baichuan camera-side server. */
internal class NativeDoorbellBridge(
    private val context: Context,
    private val connected: () -> Unit,
    private val disconnected: () -> Unit,
    private val keyframe: (Boolean) -> Unit,
    private val log: (String) -> Unit
) {
    companion object {
        const val PREF_ENABLED = "native_doorbell_enabled"
        @Volatile var active: NativeDoorbellBridge? = null
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var server: NativeCameraServer? = null
    @Volatile private var state: DeviceState? = null
    private var audio: AacAudioSource? = null
    private var viewers = 0
    private var generation = 0
    private val diagnosticSockets = mutableListOf<java.net.DatagramSocket>()
    private val diagnosticLock = Any()
    private fun recordDiagnostic(message: String) {
        android.util.Log.i("NativeDoorbell", message)
        synchronized(diagnosticLock) {
            try {
                val file = java.io.File(context.filesDir, "native-diagnostics.log")
                if (file.length() > 65536) {
                    file.copyTo(java.io.File(context.filesDir, "native-diagnostics.previous.log"), overwrite = true)
                    file.writeText("")
                }
                file.appendText("${java.time.Instant.now()} $message\n")
            } catch (_: java.io.IOException) { /* Diagnostics must not stop the camera. */ }
        }
    }

    // Advertise the TCP service; UDP media/P2P remains unsupported.
    private fun startUdpDiagnostics(device: DeviceState) {
        for (port in listOf(2000, 2015, 9000)) {
            try {
                val socket = java.net.DatagramSocket(java.net.InetSocketAddress(ListenInterface.resolve(context), port))
                diagnosticSockets.add(socket)
                Thread({
                    val buffer = ByteArray(65507)
                    var lastLog = 0L
                    while (!socket.isClosed) {
                        try {
                            val packet = java.net.DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (port != 9000) {
                                try {
                                    val replyPort = NativeDiscovery.searchPort(packet.data.copyOf(packet.length))
                                    if (now - lastLog >= 250) {
                                        val address = (context as? com.github.digitallyrefined.androidipcamera.StreamingService)?.getLocalIpAddress()
                                        if (address != null && address != "unknown") {
                                            val response = NativeDiscovery.advertisement(address, device.id, 9000)
                                            for (destination in setOf(replyPort, 3000)) {
                                                socket.send(java.net.DatagramPacket(response, response.size, packet.address, destination))
                                            }
                                            device.log("Native discovery search from ${packet.address.hostAddress}; advertised $address:9000 to UDP $replyPort/3000 (experimental legacy format)")
                                        }
                                        lastLog = now
                                    }
                                    continue
                                } catch (_: Exception) { /* Unsupported/malformed request: metadata only below. */ }
                            }
                            if (now - lastLog >= 1000) {
                                device.log("Unsupported UDP attempt from ${packet.address.hostAddress}:${packet.port} to $port (${packet.length} bytes); no response sent")
                                lastLog = now
                            }
                        } catch (_: java.io.IOException) { break }
                    }
                }, "NativeUdpDiagnostic-$port").apply { isDaemon = true; start() }
                device.log(if (port == 9000) "UDP diagnostic listener on $port (observation only)" else "Native discovery listener on UDP $port (experimental)")
            } catch (e: java.io.IOException) {
                device.log("UDP diagnostic listener unavailable on $port: ${e.javaClass.simpleName}")
            }
        }
    }
    @Volatile var status = "Native server stopped"
        private set

    @Synchronized fun start() {
        if (server != null) return
        active = this
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(PREF_ENABLED, true)) { status = "Native server disabled"; return }
        val storage = SecureStorage(context)
        val username = storage.getSecureString(SecureStorage.KEY_USERNAME, "") ?: ""
        val password = storage.getSecureString(SecureStorage.KEY_PASSWORD, "") ?: ""
        if (username.isBlank() || password.isBlank()) {
            status = "Set a username and password in Settings to start native port 9000"
            log(status); return
        }
        val id = prefs.getString("native_device_id", null) ?: ("LAB" + UUID.randomUUID().toString().replace("-", "").take(16).uppercase()).also {
            prefs.edit().putString("native_device_id", it).apply()
        }
        val device = DeviceState(username, password, id, "")
        device.logSink = java.util.function.Consumer { recordDiagnostic(it) }
        device.media.requestKeyframe = Runnable { keyframe(false) }
        device.subMedia.requestKeyframe = Runnable { keyframe(true) }
        device.audioEnabled = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && prefs.getBoolean(RtspServer.PREF_AUDIO, true)
        device.webPort = prefs.getString("server_port", "4444")?.toIntOrNull() ?: 4444
        device.rtspPort = prefs.getString(RtspServer.PREF_PORT, "8554")?.toIntOrNull() ?: 8554
        device.onvifPort = prefs.getString("onvif_port", "8000")?.toIntOrNull() ?: 8000
        device.tlsEnabled = TlsHelper.isEnabled(context)
        device.rtspEnabled = prefs.getBoolean(RtspServer.PREF_ENABLED, false)
        device.onvifEnabled = device.rtspEnabled
        try {
            val listener = NativeCameraServer(device, 9000, ListenInterface.resolve(context))
            val listenerGeneration = ++generation
            listener.onViewerConnected = Runnable {
                synchronized(this) {
                    if (listenerGeneration != generation) return@Runnable
                    viewers++
                    if (viewers == 1 && device.audioEnabled) {
                        audio = AacAudioSource(context, log) { data, pts ->
                            device.media.publish(MediaHub.Frame(data, pts, false, true))
                            device.subMedia.publish(MediaHub.Frame(data, pts, false, true))
                        }.also { it.start() }
                    }
                }
                connected()
            }
            listener.onViewerDisconnected = Runnable {
                synchronized(this) {
                    if (listenerGeneration != generation) { disconnected(); return@Runnable }
                    viewers = (viewers - 1).coerceAtLeast(0)
                    if (viewers == 0) { audio?.stop(); audio = null }
                }
                disconnected()
            }
            state = device; server = listener; listener.start()
            startUdpDiagnostics(device)
            device.log("Native TCP listener started on port 9000")
            status = "Listening on TCP 9000 • native compatibility unverified"
            log(status)
        } catch (e: Exception) { status = "Native listener failed: ${e.message}"; log(status) }
    }

    fun hasClients(): Boolean = (state?.media?.clients() ?: 0) > 0 || hasSubClients()
    fun hasSubClients(): Boolean = (state?.subMedia?.clients() ?: 0) > 0
    @Volatile var separateSubStream = false
    fun resyncSubStream() { state?.subMedia?.resync() }
    fun motion(active: Boolean) { state?.motion(active) }
    fun video(data: ByteArray, key: Boolean, pts: Long, width: Int, height: Int, fps: Int, bitrate: Int, sub: Boolean = false) {
        val device = state ?: return
        val media = if (sub) device.subMedia else device.media
        if (media.width != width || media.height != height) media.resync()
        media.width = width; media.height = height; media.fps = fps; media.bitrateKbps = bitrate / 1000
        for (nal in MediaHub.nals(data)) {
            when (nal[0].toInt() and 31) { 7 -> media.sps = nal; 8 -> media.pps = nal }
        }
        media.publish(MediaHub.Frame(if (key) media.withParameters(data) else data, pts, key, false))
    }
    fun ring() {
        scheduler.execute { state?.ring(true) }
        scheduler.schedule({ state?.ring(false) }, 1, TimeUnit.SECONDS)
    }
    fun diagnostics(): String = "RLN8-410 / 3.5.1.368 target\n$status\n" +
        "Native main viewers: ${state?.media?.clients() ?: 0}; sub: ${state?.subMedia?.clients() ?: 0}\n" +
        "Main: ${state?.media?.width}×${state?.media?.height}; sub: ${state?.subMedia?.width}×${state?.subMedia?.height}\n" +
        "Substream source: ${if (separateSubStream) "separate encoder" else "main encoder fallback"}\n" +
        "Native discovery is experimental; UDP media, two-way talk and AI are not implemented.\n" +
        (state?.logs() ?: "No native connections yet.")

    @Synchronized fun stop() {
        diagnosticSockets.forEach { it.close() }; diagnosticSockets.clear()
        server?.close(); server = null
        synchronized(this) { generation++; viewers = 0; audio?.stop(); audio = null }
        status = "Native server stopped"
    }
    fun destroy() { stop(); scheduler.shutdownNow(); if (active === this) active = null }
}
