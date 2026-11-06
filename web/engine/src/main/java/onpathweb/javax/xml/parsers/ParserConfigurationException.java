package onpathweb.javax.xml.parsers;

/** Web stand-in for javax.xml.parsers.ParserConfigurationException (never thrown here). */
public class ParserConfigurationException extends Exception {
  public ParserConfigurationException(String message) { super(message); }
}
