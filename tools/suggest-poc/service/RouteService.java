import btools.router.OsmNodeNamed;
import btools.router.OsmNogoPolygon;
import btools.router.OsmPathElement;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Tour-suggestions POC: a thin, long-running BRouter service (docs/design/tour-suggestions.md).
 * It only routes – candidates, scoring and selection live in Python so they change without a compile.
 *
 * POST /route, body = lines:
 *   profile=hiking-mountain            (file name in the profile dir, without .brf)
 *   kv=consider_forest=1               (profile parameter override; any number of lines)
 *   pts=lon,lat;lon,lat;...            (from, vias, to)
 *   nogo=weight;lon,lat;lon,lat;...    (weighted nogo polyline; any number of lines)
 *   timeout=8000
 * Answer: JSON {dist, ascend, seconds, ms, coords:[[lon,lat,ele],..], msgs:["Longitude\tLatitude\t...", rows...]}
 * or {error:"..."}. msgs = BRouter's aggregated messages (one row per run of equal way tags, full tags).
 */
public class RouteService {
  static File segDir;
  static File profileDir;

  public static void main(String[] a) throws Exception {
    segDir = new File(a[0]);
    profileDir = new File(a[1]);
    int port = Integer.parseInt(a[2]);
    int threads = a.length > 3 ? Integer.parseInt(a[3]) : 4;
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    s.createContext("/route", RouteService::handle);
    s.createContext("/graph", RouteService::handleGraph);
    s.createContext("/ping", ex -> send(ex, "{\"ok\":true}"));
    s.setExecutor(Executors.newFixedThreadPool(threads));
    s.start();
    System.out.println("route service on 127.0.0.1:" + port + " segments=" + segDir + " profiles=" + profileDir);
  }

  static void send(HttpExchange ex, String body) throws java.io.IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(200, b.length);
    try (OutputStream o = ex.getResponseBody()) { o.write(b); }
  }

  static int ilon(double lon) { return (int) ((lon + 180.0) * 1_000_000.0 + 0.5); }
  static int ilat(double lat) { return (int) ((lat + 90.0) * 1_000_000.0 + 0.5); }

  static void handle(HttpExchange ex) throws java.io.IOException {
    String out;
    try {
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      out = route(body);
    } catch (Throwable t) {
      out = "{\"error\":" + q(String.valueOf(t)) + "}";
    }
    send(ex, out);
  }

  static String route(String body) {
    String profile = "hiking-mountain";
    long timeout = 8000;
    Map<String, String> kv = new HashMap<>();
    kv.put("processUnusedTags", "1");
    kv.put("turnInstructionMode", "0");
    kv.put("use_dynamic_range", "0");
    kv.put("waypointCatchingRange", "400");
    List<OsmNodeNamed> nodes = new ArrayList<>();
    List<OsmNodeNamed> nogos = new ArrayList<>();
    for (String line : body.split("\n")) {
      line = line.trim();
      int e = line.indexOf('=');
      if (e < 0) continue;
      String k = line.substring(0, e), v = line.substring(e + 1);
      switch (k) {
        case "profile": profile = v; break;
        case "timeout": timeout = Long.parseLong(v); break;
        case "kv": { int i = v.indexOf('='); kv.put(v.substring(0, i), v.substring(i + 1)); break; }
        case "pts": {
          String[] ps = v.split(";");
          for (int i = 0; i < ps.length; i++) {
            String[] c = ps[i].split(",");
            OsmNodeNamed n = new OsmNodeNamed();
            n.name = i == 0 ? "from" : i == ps.length - 1 ? "to" : "via" + i;
            n.ilon = ilon(Double.parseDouble(c[0]));
            n.ilat = ilat(Double.parseDouble(c[1]));
            nodes.add(n);
          }
          break;
        }
        case "nogo": {
          String[] ps = v.split(";");
          OsmNogoPolygon p = new OsmNogoPolygon(false);
          for (int i = 1; i < ps.length; i++) {
            String[] c = ps[i].split(",");
            p.addVertex(ilon(Double.parseDouble(c[0])), ilat(Double.parseDouble(c[1])));
          }
          p.name = "nogo" + nogos.size();
          p.nogoWeight = Double.parseDouble(ps[0]);
          p.isNogo = true;
          p.calcBoundingCircle();
          nogos.add(p);
          break;
        }
        default: break;
      }
    }
    RoutingContext rc = new RoutingContext();
    rc.localFunction = new File(profileDir, profile + ".brf").getAbsolutePath();
    rc.keyValues = kv;
    if (!nogos.isEmpty()) {
      RoutingContext.prepareNogoPoints(nogos);
      rc.nogopoints = nogos;
    }
    long t0 = System.currentTimeMillis();
    RoutingEngine engine = new RoutingEngine(null, null, segDir, nodes, rc);
    engine.quite = true;
    engine.doRun(timeout);
    long ms = System.currentTimeMillis() - t0;
    String err = engine.getErrorMessage();
    OsmTrack t = engine.getFoundTrack();
    if (err != null || t == null) return "{\"error\":" + q(String.valueOf(err)) + ",\"ms\":" + ms + "}";
    StringBuilder sb = new StringBuilder(1 << 16);
    sb.append("{\"dist\":").append(t.distance).append(",\"ascend\":").append(t.ascend)
      .append(",\"seconds\":").append(t.getTotalSeconds()).append(",\"ms\":").append(ms).append(",\"coords\":[");
    boolean first = true;
    for (OsmPathElement n : t.nodes) {
      if (!first) sb.append(',');
      first = false;
      sb.append('[').append((n.getILon() - 180_000_000) / 1e6).append(',').append((n.getILat() - 90_000_000) / 1e6)
        .append(',').append(Math.round(n.getElev() * 10) / 10.0).append(']');
    }
    sb.append("],\"msgs\":[");
    first = true;
    for (String m : t.aggregateMessages()) {
      if (m == null) continue;
      if (!first) sb.append(',');
      first = false;
      sb.append(q(m));
    }
    sb.append("]}");
    return sb.toString();
  }

  static void handleGraph(HttpExchange ex) throws java.io.IOException {
    String out;
    try {
      String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      synchronized (RouteService.class) { out = graph(body); }
    } catch (Throwable t) {
      java.io.StringWriter w = new java.io.StringWriter();
      t.printStackTrace(new java.io.PrintWriter(w));
      out = "ERR " + w;
    }
    byte[] b = out.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
    ex.sendResponseHeaders(200, b.length);
    try (OutputStream o = ex.getResponseBody()) { o.write(b); }
  }

  /**
   * POST /graph, body = lines: profile=…, kv=… (as /route), at=lon,lat, radius=<m>.
   * The way network around the point, read straight from BRouter's node cache (the same data and the same profile
   * evaluation as routing). Answer = text lines:
   *   S <node>                          the graph node next to the start
   *   N <id> <lon> <lat> <ele>
   *   T <tagId> <BRouter way tags, "k=v k=v">
   *   E <a> <b> <metres> <costfactor a→b> <costfactor b→a> <tagId> <lon,lat;lon,lat… of the pieces between a and b>
   * costfactor ≥ 9999 = not allowed for this profile in that direction.
   */
  static String graph(String body) throws Exception {
    String profile = "hiking-mountain";
    double lon = 0, lat = 0, radius = 3000;
    Map<String, String> kv = new HashMap<>();
    kv.put("processUnusedTags", "1");
    for (String line : body.split("\n")) {
      line = line.trim();
      int e = line.indexOf('=');
      if (e < 0) continue;
      String k = line.substring(0, e), v = line.substring(e + 1);
      switch (k) {
        case "profile": profile = v; break;
        case "kv": { int i = v.indexOf('='); kv.put(v.substring(0, i), v.substring(i + 1)); break; }
        case "at": { String[] c = v.split(","); lon = Double.parseDouble(c[0]); lat = Double.parseDouble(c[1]); break; }
        case "radius": radius = Double.parseDouble(v); break;
        default: break;
      }
    }
    long t0 = System.currentTimeMillis();
    RoutingContext rc = new RoutingContext();
    rc.localFunction = new File(profileDir, profile + ".brf").getAbsolutePath();
    rc.keyValues = kv;
    btools.router.ProfileCache.parseProfile(rc);
    btools.mapaccess.NodesCache nc = new btools.mapaccess.NodesCache(segDir, rc.expctxWay, rc.forceSecondaryData,
      2000L * 1024 * 1024, null, true);
    try {
      btools.mapaccess.MatchedWaypoint mwp = new btools.mapaccess.MatchedWaypoint();
      mwp.waypoint = new btools.mapaccess.OsmNode(ilon(lon), ilat(lat));
      mwp.name = "from";
      List<btools.mapaccess.MatchedWaypoint> l = new ArrayList<>();
      l.add(mwp);
      nc.matchWaypointsToNodes(l, 400, new btools.mapaccess.OsmNodePairSet(1));
      // as RoutingEngine does: the cache used for matching is not the one to walk – a fresh one on the same files
      nc = new btools.mapaccess.NodesCache(segDir, rc.expctxWay, rc.forceSecondaryData, 2000L * 1024 * 1024, nc, true);
      btools.mapaccess.OsmNode centre = new btools.mapaccess.OsmNode(ilon(lon), ilat(lat));
      btools.mapaccess.OsmNode start = nc.getGraphNode(mwp.node1);
      StringBuilder sb = new StringBuilder(1 << 22);
      Map<btools.mapaccess.OsmNode, Integer> ids = new java.util.IdentityHashMap<>();
      Map<String, Integer> tagIds = new HashMap<>();
      java.util.Set<btools.mapaccess.OsmLink> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      java.util.ArrayDeque<btools.mapaccess.OsmNode> queue = new java.util.ArrayDeque<>();
      if (!nc.obtainNonHollowNode(start)) return "ERR start node not loadable";
      ids.put(start, 0);
      queue.add(start);
      sb.append("S 0\n");
      node(sb, 0, start);
      int edges = 0;
      while (!queue.isEmpty()) {
        btools.mapaccess.OsmNode n = queue.poll();
        nc.expandHollowLinkTargets(n);
        for (btools.mapaccess.OsmLink link = n.firstlink; link != null; link = link.getNext(n)) {
          if (link.descriptionBitmap == null || !seen.add(link)) continue;
          btools.mapaccess.OsmNode t = link.getTarget(n);
          if (t == null || !nc.obtainNonHollowNode(t)) continue;
          Integer tid = ids.get(t);
          if (tid == null) {
            tid = ids.size();
            ids.put(t, tid);
            node(sb, tid, t);
            if (t.calcDistance(centre) <= radius) queue.add(t);
          }
          boolean rev = link.isReverse(n);
          rc.expctxWay.evaluate(rev, link.descriptionBitmap);
          float cf = rc.expctxWay.getCostfactor();
          rc.expctxWay.evaluate(!rev, link.descriptionBitmap);
          float cb = rc.expctxWay.getCostfactor();
          String tags = rc.expctxWay.getKeyValueDescription(rev, link.descriptionBitmap);
          Integer tg = tagIds.get(tags);
          if (tg == null) {
            tg = tagIds.size();
            tagIds.put(tags, tg);
            sb.append("T ").append(tg).append(' ').append(tags).append('\n');
          }
          StringBuilder g = new StringBuilder();
          double len = 0;
          int plon = n.ilon, plat = n.ilat;
          btools.mapaccess.OsmTransferNode tn = link.geometry == null ? null
            : rc.geometryDecoder.decodeGeometry(link.geometry, n, t, rev);
          for (; tn != null; tn = tn.next) {
            len += dist(plon, plat, tn.ilon, tn.ilat);
            plon = tn.ilon; plat = tn.ilat;
            if (g.length() > 0) g.append(';');
            g.append(fmt((plon - 180_000_000) / 1e6)).append(',').append(fmt((plat - 90_000_000) / 1e6));
          }
          len += dist(plon, plat, t.ilon, t.ilat);
          sb.append("E ").append(ids.get(n)).append(' ').append(tid).append(' ').append(Math.round(len * 10) / 10.0)
            .append(' ').append(cf).append(' ').append(cb).append(' ').append(tg).append(' ').append(g).append('\n');
          edges++;
        }
      }
      sb.append("# nodes ").append(ids.size()).append(" edges ").append(edges).append(" ms ")
        .append(System.currentTimeMillis() - t0).append('\n');
      return sb.toString();
    } finally {
      nc.close();
      btools.router.ProfileCache.releaseProfile(rc);
    }
  }

  static String fmt(double d) { return String.valueOf(Math.round(d * 1e6) / 1e6); }

  static void node(StringBuilder sb, int id, btools.mapaccess.OsmNode n) {
    sb.append("N ").append(id).append(' ').append(fmt((n.ilon - 180_000_000) / 1e6)).append(' ')
      .append(fmt((n.ilat - 90_000_000) / 1e6)).append(' ').append(Math.round(n.getElev() * 10) / 10.0).append('\n');
  }

  static double dist(int lon1, int lat1, int lon2, int lat2) {
    double ky = 0.11132;  // metres per micro-degree
    double kx = ky * Math.cos(Math.toRadians((lat1 + lat2) / 2e6 - 90.0));
    return Math.hypot((lon1 - lon2) * kx, (lat1 - lat2) * ky);
  }

  static String q(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"': b.append("\\\""); break;
        case '\\': b.append("\\\\"); break;
        case '\n': b.append("\\n"); break;
        case '\r': break;
        case '\t': b.append("\\t"); break;
        default: if (c < 0x20) b.append(' '); else b.append(c);
      }
    }
    return b.append('"').toString();
  }
}
