package onpathweb.org.xml.sax;

/** Web stand-in for org.xml.sax.SAXException (TeaVM has no XML parser; see onpathweb/README in XmlSubstitution). */
public class SAXException extends Exception {
  public SAXException(String message) { super(message); }
  public SAXException(String message, Exception cause) { super(message, cause); }
}
