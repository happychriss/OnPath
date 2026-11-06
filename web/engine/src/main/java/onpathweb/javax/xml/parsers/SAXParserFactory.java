package onpathweb.javax.xml.parsers;

import onpathweb.org.xml.sax.SAXException;

/**
 * Web stand-in for javax.xml.parsers.SAXParserFactory (TeaVM has no XML parser): hands out the small non-validating
 * parser below. Namespaces are never resolved (qName = the name as written), external entities are never read –
 * the settings the app's GpxParser asks for anyway.
 */
public class SAXParserFactory {
  private boolean namespaceAware;
  private boolean validating;

  public static SAXParserFactory newInstance() { return new SAXParserFactory(); }

  public void setNamespaceAware(boolean v) { namespaceAware = v; }
  public boolean isNamespaceAware() { return namespaceAware; }
  public void setValidating(boolean v) { validating = v; }
  public boolean isValidating() { return validating; }

  /** Every feature is accepted: this parser never loads external DTDs or entities. */
  public void setFeature(String name, boolean value) { }

  public SAXParser newSAXParser() throws ParserConfigurationException, SAXException { return new SAXParser(); }
}
