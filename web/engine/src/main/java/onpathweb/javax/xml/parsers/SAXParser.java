package onpathweb.javax.xml.parsers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import onpathweb.org.xml.sax.Attributes;
import onpathweb.org.xml.sax.SAXException;
import onpathweb.org.xml.sax.helpers.DefaultHandler;

/**
 * Web stand-in for javax.xml.parsers.SAXParser: a small non-validating, non-namespace-aware XML reader for the app's
 * GpxParser (TeaVM has no javax.xml). It reports what the JDK's parser reports for well-formed files – elements with
 * their qName and attributes, text with the five predefined entities and character references resolved, CDATA as
 * text – and, like the JDK's, throws [SAXException] at the first well-formedness error (mismatched or unclosed tags,
 * a stray '&' or '<', an unknown entity, text outside the root). GpxParser then keeps what was read and runs its
 * recovery scan, so a broken file gives the same result as on the phone. DOCTYPEs are skipped (no entities read).
 */
public class SAXParser {
  public void parse(InputStream in, DefaultHandler h) throws SAXException, IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[65536];
    int n;
    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    new Reader(decode(out.toByteArray()), h).run();
  }

  /** UTF-8 (BOM dropped), UTF-16 by BOM, ISO-8859-1 / windows-1252 when the declaration says so. */
  static String decode(byte[] b) {
    if (b.length >= 2 && (b[0] & 0xff) == 0xfe && (b[1] & 0xff) == 0xff) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
    if (b.length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
    int off = b.length >= 3 && (b[0] & 0xff) == 0xef && (b[1] & 0xff) == 0xbb && (b[2] & 0xff) == 0xbf ? 3 : 0;
    String head = new String(b, off, Math.min(200, b.length - off), StandardCharsets.ISO_8859_1).toLowerCase();
    int decl = head.startsWith("<?xml") ? head.indexOf("?>") : -1;
    if (decl > 0) {
      String d = head.substring(0, decl);
      if (d.contains("iso-8859-1") || d.contains("latin1") || d.contains("windows-1252") || d.contains("cp1252"))
        return new String(b, off, b.length - off, StandardCharsets.ISO_8859_1);
    }
    return new String(b, off, b.length - off, StandardCharsets.UTF_8);
  }

  private static final class Attrs implements Attributes {
    final List<String> names = new ArrayList<>();
    final List<String> values = new ArrayList<>();
    public int getLength() { return names.size(); }
    public String getQName(int i) { return i >= 0 && i < names.size() ? names.get(i) : null; }
    public String getLocalName(int i) { return ""; }
    public String getValue(int i) { return i >= 0 && i < values.size() ? values.get(i) : null; }
    public String getValue(String q) { return getValue(getIndex(q)); }
    public String getURI(int i) { return ""; }
    public String getType(int i) { return "CDATA"; }
    public int getIndex(String q) { return names.indexOf(q); }
  }

  private static final class Reader {
    private final String s;
    private final DefaultHandler h;
    private int p;
    private final ArrayList<String> open = new ArrayList<>();
    private boolean rootSeen;

    Reader(String s, DefaultHandler h) { this.s = s; this.h = h; }

    private SAXException err(String what) { return new SAXException(what + " (at character " + p + ")"); }

    void run() throws SAXException {
      h.startDocument();
      StringBuilder text = new StringBuilder();
      while (p < s.length()) {
        char c = s.charAt(p);
        if (c == '<') {
          flush(text);
          if (s.startsWith("<?", p)) { int e = s.indexOf("?>", p + 2); if (e < 0) throw err("unclosed processing instruction"); p = e + 2; }
          else if (s.startsWith("<!--", p)) { int e = s.indexOf("-->", p + 4); if (e < 0) throw err("unclosed comment"); p = e + 3; }
          else if (s.startsWith("<![CDATA[", p)) {
            if (open.isEmpty()) throw err("CDATA outside the root element");
            int e = s.indexOf("]]>", p + 9); if (e < 0) throw err("unclosed CDATA section");
            text.append(s, p + 9, e); p = e + 3; flush(text);
          }
          else if (s.startsWith("<!", p)) skipDeclaration();
          else if (s.startsWith("</", p)) endTag();
          else startTag();
        } else if (c == '&') {
          if (open.isEmpty()) throw err("reference outside the root element");
          text.append(entity());
        } else {
          if (open.isEmpty() && !isSpace(c)) throw err("content outside the root element");
          if (!open.isEmpty()) text.append(c);
          p++;
        }
      }
      flush(text);
      if (!open.isEmpty()) throw err("element <" + open.get(open.size() - 1) + "> is not closed");
      if (!rootSeen) throw err("no root element");
      h.endDocument();
    }

    private void flush(StringBuilder text) throws SAXException {
      if (text.length() == 0) return;
      char[] ch = text.toString().toCharArray();
      h.characters(ch, 0, ch.length);
      text.setLength(0);
    }

    private void skipDeclaration() throws SAXException {
      // <!DOCTYPE …> with an optional [internal subset]
      int depth = 0;
      for (int i = p + 2; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '[') depth++;
        else if (c == ']') depth--;
        else if (c == '>' && depth <= 0) { p = i + 1; return; }
      }
      throw err("unclosed declaration");
    }

    private String name() throws SAXException {
      int st = p;
      while (p < s.length()) {
        char c = s.charAt(p);
        if (isSpace(c) || c == '>' || c == '/' || c == '=' || c == '<' || c == '"' || c == '\'') break;
        p++;
      }
      if (p == st) throw err("name expected");
      char f = s.charAt(st);
      if (Character.isDigit(f) || f == '-' || f == '.') throw err("bad name");
      return s.substring(st, p);
    }

    private void spaces() { while (p < s.length() && isSpace(s.charAt(p))) p++; }

    private void startTag() throws SAXException {
      if (open.isEmpty() && rootSeen) throw err("second root element");
      p++;
      String name = name();
      Attrs a = new Attrs();
      while (true) {
        int before = p;
        spaces();
        if (p >= s.length()) throw err("unclosed tag <" + name + ">");
        char c = s.charAt(p);
        if (c == '>') { p++; open.add(name); rootSeen = true; h.startElement("", "", name, a); return; }
        if (c == '/') {
          if (!s.startsWith("/>", p)) throw err("'>' expected");
          p += 2; rootSeen = true;
          h.startElement("", "", name, a);
          h.endElement("", "", name);
          return;
        }
        if (p == before) throw err("space expected before an attribute");
        String an = name();
        spaces();
        if (p >= s.length() || s.charAt(p) != '=') throw err("'=' expected after " + an);
        p++;
        spaces();
        if (p >= s.length()) throw err("attribute value expected");
        char q = s.charAt(p);
        if (q != '"' && q != '\'') throw err("quoted attribute value expected");
        p++;
        StringBuilder v = new StringBuilder();
        while (true) {
          if (p >= s.length()) throw err("unclosed attribute value");
          char d = s.charAt(p);
          if (d == q) { p++; break; }
          if (d == '<') throw err("'<' in an attribute value");
          if (d == '&') { v.append(entity()); continue; }
          // attribute value normalisation (XML 1.0 §3.3.3): white space characters become spaces
          v.append(d == '\n' || d == '\r' || d == '\t' ? ' ' : d);
          p++;
        }
        if (a.names.contains(an)) throw err("attribute " + an + " given twice");
        a.names.add(an);
        a.values.add(v.toString());
      }
    }

    private void endTag() throws SAXException {
      p += 2;
      String name = name();
      spaces();
      if (p >= s.length() || s.charAt(p) != '>') throw err("'>' expected");
      p++;
      if (open.isEmpty() || !open.get(open.size() - 1).equals(name)) throw err("end tag </" + name + "> does not match");
      open.remove(open.size() - 1);
      h.endElement("", "", name);
    }

    private String entity() throws SAXException {
      int e = s.indexOf(';', p);
      if (e < 0 || e - p > 12) throw err("stray '&'");
      String ref = s.substring(p + 1, e);
      p = e + 1;
      switch (ref) {
        case "amp": return "&";
        case "lt": return "<";
        case "gt": return ">";
        case "quot": return "\"";
        case "apos": return "'";
        default:
          if (ref.startsWith("#")) {
            try {
              int cp = ref.startsWith("#x") || ref.startsWith("#X") ? Integer.parseInt(ref.substring(2), 16) : Integer.parseInt(ref.substring(1));
              return new String(Character.toChars(cp));
            } catch (RuntimeException ex) {
              throw err("bad character reference &" + ref + ";");
            }
          }
          throw err("unknown entity &" + ref + ";");
      }
    }

    private static boolean isSpace(char c) { return c == ' ' || c == '\n' || c == '\r' || c == '\t'; }
  }
}
