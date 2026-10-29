package onpathweb.org.xml.sax;

/** Web stand-in for org.xml.sax.Attributes: the calls a non-namespace-aware SAX handler makes. */
public interface Attributes {
  int getLength();
  String getQName(int index);
  String getLocalName(int index);
  String getValue(int index);
  String getValue(String qName);
  String getURI(int index);
  String getType(int index);
  int getIndex(String qName);
}
