package dev.doorbell.core;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class Xml {
    private Xml() {}
    public static Document parse(String xml) throws Exception {
        // Reolink client templates may include whitespace before the XML declaration.
        xml = xml.trim();
        if (xml.toUpperCase(java.util.Locale.ROOT).contains("<!DOCTYPE") || xml.toUpperCase(java.util.Locale.ROOT).contains("<!ENTITY")) throw new IOException("DTD prohibited");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true); f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
    public static String text(Document d, String tag) { NodeList n=d.getElementsByTagNameNS("*",tag); return n.getLength()>0 ? n.item(0).getTextContent() : ""; }
    public static String escape(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;"); }
    public static String body(String s) { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><body>"+s+"</body>"; }
}
