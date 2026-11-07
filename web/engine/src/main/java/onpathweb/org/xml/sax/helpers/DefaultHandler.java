package onpathweb.org.xml.sax.helpers;

import onpathweb.org.xml.sax.Attributes;
import onpathweb.org.xml.sax.SAXException;

/** Web stand-in for org.xml.sax.helpers.DefaultHandler: every callback does nothing. */
public class DefaultHandler {
  public void startDocument() throws SAXException { }
  public void endDocument() throws SAXException { }
  public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException { }
  public void endElement(String uri, String localName, String qName) throws SAXException { }
  public void characters(char[] ch, int start, int length) throws SAXException { }
  public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException { }
  public void processingInstruction(String target, String data) throws SAXException { }
}
