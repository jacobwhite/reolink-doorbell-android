import dev.doorbell.core.*;
/** Local protocol harness for independent client compatibility checks; no Android APIs. */
public class NativeHarness {
    public static void main(String[] args) throws Exception {
        DeviceState device = new DeviceState("admin", "test-only-password", "LAB0000000000000001", "127.0.0.1");
        NativeCameraServer server = new NativeCameraServer(device, 19000, java.net.InetAddress.getLoopbackAddress());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Test native server on port 19000");
        Thread.sleep(60000);
        server.close();
    }
}
