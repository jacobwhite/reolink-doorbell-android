package dev.doorbell.core;


/** Read-only protocol metadata. Values describe configured services and actual encoder output. */
final class NativeMetadata {
    private NativeMetadata() {}
    static String deviceInfo(DeviceState state) {
        return "<DeviceInfo version=\"1.1\"><firmVersion>00000000000000</firmVersion>" +
            "<IOInputPortNum>0</IOInputPortNum><IOOutputPortNum>0</IOOutputPortNum><diskNum>0</diskNum>" +
            // smart_bell, rather than ipc, enables Visitor support/schedules on RLN8-410.
            "<type>smart_bell</type><channelNum>1</channelNum><audioNum>"+(state.audioEnabled?1:0)+"</audioNum>" +
            "<ipChannel>0</ipChannel><analogChnNum>1</analogChnNum><resolution><resolutionName>" +
            state.media.width+"*"+state.media.height+"</resolutionName><width>"+state.media.width+
            "</width><height>"+state.media.height+"</height></resolution><language>English</language>" +
            "<sdCard>0</sdCard><ptzMode>none</ptzMode><typeInfo>IPC</typeInfo><softVer>0</softVer>" +
            "<hardVer>0</hardVer><panelVer>0</panelVer><hdChannel1>0</hdChannel1><hdChannel2>0</hdChannel2>" +
            "<hdChannel3>0</hdChannel3><hdChannel4>0</hdChannel4><norm>NTSC</norm><osdFormat>YMD</osdFormat>" +
            "<B485>0</B485><supportAutoUpdate>0</supportAutoUpdate><userVer>1</userVer><sleep>0</sleep></DeviceInfo>";
    }
    // Channel label only; overlays remain disabled because the encoder does not draw them.
    static String osd() {
        return "<OsdChannelName version=\"1.1\"><channelId>0</channelId><name>Doorbell</name>" +
            "<enable>0</enable><topLeftX>0</topLeftX><topLeftY>0</topLeftY><enWatermark>0</enWatermark><enBgcolor>0</enBgcolor></OsdChannelName>" +
            "<OsdDatetime version=\"1.1\"><channelId>0</channelId><enable>0</enable><topLeftX>0</topLeftX><topLeftY>0</topLeftY>" +
            "<width>0</width><height>0</height><language>English</language></OsdDatetime>";
    }
    static String streamInfo(DeviceState state) {
        return "<StreamInfoList version=\"1.1\"><StreamInfo><channelBits>1</channelBits>" +
            encodeTable("mainStream", state.media) + encodeTable("subStream", state.subMedia) +
            "</StreamInfo></StreamInfoList>";
    }
    private static String encodeTable(String name, MediaHub media) {
        return "<encodeTable><type>" + name + "</type><resolution><width>" + media.width +
            "</width><height>" + media.height + "</height></resolution><defaultFramerate>" + media.fps +
            "</defaultFramerate><defaultBitrate>" + media.bitrateKbps + "</defaultBitrate><framerateTable>" +
            media.fps + "</framerateTable><bitrateTable>" + media.bitrateKbps + "</bitrateTable></encodeTable>";
    }
    static String encoding(DeviceState state) {
        return "<Compression version=\"1.1\"><channelId>0</channelId><isNoTranslateFrame>1</isNoTranslateFrame>" + encoding("mainStream",state.media,state.audioEnabled) +
            encoding("subStream",state.subMedia,state.audioEnabled) + "</Compression>";
    }
    private static String encoding(String name, MediaHub media, boolean audio) {
        return "<" + name + "><resolutionName>" + media.width + "*" + media.height +
            "</resolutionName><encoderType>vbr</encoderType><encoderProfile>high</encoderProfile><videoEncType>0</videoEncType><width>" + media.width + "</width><height>" +
            media.height + "</height><frame>" + media.fps + "</frame><bitRate>" + media.bitrateKbps +
            "</bitRate><audio>" + (audio ? 1 : 0) + "</audio></" + name + ">";
    }
    static String ports(DeviceState s, int nativePort) {
        return port("Server",nativePort,true) + port("Http",s.tlsEnabled ? 80 : s.webPort,!s.tlsEnabled) +
            port("Https",s.tlsEnabled ? s.webPort : 443,s.tlsEnabled) + port("Rtsp",s.rtspPort,s.rtspEnabled) +
            port("Onvif",s.onvifPort,s.onvifEnabled) + port("Rtmp",1935,false);
    }
    private static String port(String name, int port, boolean enabled) {
        return "<"+name+"Port version=\"1.1\"><"+name.toLowerCase(java.util.Locale.ROOT)+"Port>"+port+
            "</"+name.toLowerCase(java.util.Locale.ROOT)+"Port><enable>"+(enabled?1:0)+"</enable></"+name+"Port>";
    }
    static String abilities(DeviceState s) {
        return "<AbilityInfo version=\"1.1\"><userName>"+Xml.escape(s.username)+"</userName>"+
            "<system><subModule><abilityValue>version_ro,general_rw</abilityValue></subModule></system>"+
            "<network><subModule><abilityValue>netPort_ro</abilityValue></subModule></network>"+
            "<video><subModule><channelId>0</channelId><abilityValue>preview_rw,streamTable_ro,enc_ro</abilityValue></subModule></video></AbilityInfo>";
    }
    static String support(DeviceState s) {
        return "<Support version=\"1.1\"><IOInputPortNum>0</IOInputPortNum><IOOutputPortNum>0</IOOutputPortNum>"+
            "<diskNum>0</diskNum><channelNum>1</channelNum><audioNum>"+(s.audioEnabled?1:0)+"</audioNum>"+
            "<ptzMode>none</ptzMode><ptzCfg>0</ptzCfg><b485>0</b485><autoUpdate>0</autoUpdate>"+
            "<pushAlarm>0</pushAlarm><ftp>0</ftp><email>0</email><record>0</record><audioTalk>0</audioTalk>"+
            // Some NVR IPC adapters consume host-level camera feature fields,
            // while client libraries consume the channel item below.
            "<doorbellVersion>1</doorbellVersion><motion>1</motion><aitype>0</aitype><eventTypeVersion>129</eventTypeVersion>"+
            "<noExternStream>1</noExternStream><netPort>1</netPort><rtsp>"+(s.rtspEnabled?1:0)+"</rtsp><onvif>"+(s.onvifEnabled?1:0)+"</onvif>"+
            "<item><chnID>0</chnID><ptzType>0</ptzType><noAudio>"+(s.audioEnabled?0:1)+"</noAudio>"+
            "<battery>0</battery><ispCfg>0</ispCfg><osdCfg>0</osdCfg><ledCtrl>0</ledCtrl>"+
            // Experimental event mask: MD (bit 0) and Visitor (bit 7), inferred
            // from published camera/doorbell capability differences. NVR UI
            // recognition must be verified; do not advertise AI detections.
            "<motion>1</motion><aitype>0</aitype><eventTypeVersion>129</eventTypeVersion><doorbellVersion>1</doorbellVersion></item></Support>";
    }
}
