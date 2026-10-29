package com.onpath.web.teavm;

import org.teavm.extension.spi.substitution.SimpleSubstitutionPolicy;
import org.teavm.extension.spi.substitution.SubstitutionSink;

/**
 * TeaVM has no javax.xml / org.xml.sax. For the Wasm build only, these classes are taken from our small stand-ins in
 * `onpathweb.<same package>` (web/engine/src/main/java/onpathweb/…), so the app's gpx/GpxParser.kt compiles unchanged.
 * The JVM build and tests use the JDK's own parser. Registered in META-INF/services.
 */
public class XmlSubstitution extends SimpleSubstitutionPolicy {
  @Override
  public void contribute(SubstitutionSink sink) {
    sink.selectClasses(inPackage("javax.xml.parsers").or(inPackage("org.xml.sax", true))).packagePrefix("onpathweb");
  }
}
