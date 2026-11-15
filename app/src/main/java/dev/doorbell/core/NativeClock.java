package dev.doorbell.core;

import org.w3c.dom.Document;
import java.time.*;
import java.util.Set;

/** Camera-local clock for NVR synchronization; never changes the Android system clock. */
final class NativeClock {
    private long epochMillis = System.currentTimeMillis(), anchorNanos = System.nanoTime();
    private int timezone = 0, timeFormat = 0;
    private String osdFormat = "YMD";
    synchronized boolean set(Document xml) {
        try {
            int zone = number(xml,"timeZone"), format = number(xml,"timeFormat");
            String osd = Xml.text(xml,"osdFormat");
            if (Math.abs((long)zone)>14*3600 || (format!=0 && format!=1) || !Set.of("YMD","MDY","DMY").contains(osd)) return false;
            int year=number(xml,"year");
            if(year<1970 || year>2099)return false;
            LocalDateTime requested=LocalDateTime.of(year,number(xml,"month"),number(xml,"day"),number(xml,"hour"),number(xml,"minute"),number(xml,"second"));
            long epoch=requested.toInstant(ZoneOffset.ofTotalSeconds(-zone)).toEpochMilli();
            // Commit only after every field is validated.
            epochMillis=epoch;anchorNanos=System.nanoTime();timezone=zone;timeFormat=format;osdFormat=osd;
            return true;
        } catch(RuntimeException e) { return false; }
    }
    private int number(Document xml,String field) { return Integer.parseInt(Xml.text(xml,field)); }
    synchronized String xml() {
        ZonedDateTime t=Instant.ofEpochMilli(epochMillis+(System.nanoTime()-anchorNanos)/1_000_000L).atZone(ZoneOffset.ofTotalSeconds(-timezone));
        return "<SystemGeneral version=\"1.1\"><timeZone>"+timezone+"</timeZone><osdFormat>"+osdFormat+"</osdFormat><timeFormat>"+timeFormat+"</timeFormat>"+
            "<year>"+t.getYear()+"</year><month>"+t.getMonthValue()+"</month><day>"+t.getDayOfMonth()+
            "</day><hour>"+t.getHour()+"</hour><minute>"+t.getMinute()+"</minute><second>"+t.getSecond()+"</second></SystemGeneral>";
    }
}
