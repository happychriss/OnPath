import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "The router decides, the map shows it" (docs/design/map-vs-router-audit.md, owner decisions 2026-10-22 (3) / (4)):
 * evaluates OnPath's own BRouter profiles per way tag set with BRouter's expression engine (no routing, no segments)
 * and writes one class per profile group. tools/extract_overlays.py --wayclass runs this and puts the classes on the
 * overlay tile layers "paths" and "streets" (and blocked barrier nodes on "barriers").
 *
 * Usage:  java -jar wayclass.jar --profiles DIR [--costs]  < requests  > answers
 *         java -jar wayclass.jar --profiles DIR --hash               (profile hash only)
 *         java -jar wayclass.jar --profiles DIR --selftest           (known tag sets, exit 1 on a wrong class)
 *   DIR = lookups.dat + hiking-mountain.brf, trekking.brf, touring.brf, mtb.brf
 *         (app/src/main/assets/brouter, the files the app routes with)
 *
 * Requests (UTF-8, one per line, tab-separated; tag values must not contain tabs / newlines):
 *   W  id  key=value  key=value ...          a way: its OSM tags + BRouter's route pseudo-tags (route_hiking_lwn=yes …)
 *   N  id  wayId,wayId,...  key=value ...    a barrier node: its tags + the W ids of the ways through it (sent before)
 * Answers (first line "H  <profile hash>"):
 *   W  id  ch  ct  cf  cm  cr                classes 0 = good, 1 = possible, 2 = forbidden, 3 = too hard;
 *                                            cr = bikes' one-way:
 *                                            1 = only along the way's direction, -1 = only against it, 0 = both
 *   N  id  mask                              profiles the node blocks: 1 hike, 2 trekking, 4 touring, 8 MTB
 *   --costs appends the raw forward / reverse costfactors per profile (threshold tuning, consistency test).
 *
 * Profile groups (BRouterEngine.profileFile): ch = Hike (hiking-mountain), ct = Bike trekking (trekking),
 * cf = Bike touring + E-bike (touring), cm = MTB + MTB e-bike (mtb). E-bike / E-MTB change only power, speed
 * and mass – the same costs.
 *
 * Hike: the SAC limit is the user's runtime setting, so it is NOT baked in: sac_scale and mtb:scale are left out of the
 * hike evaluation (the map keeps "sac" and the app applies "sac > limit = forbidden" itself). SAC_scale_preferred = 3
 * (the app's default T3) only shifts every istrack way by the same small amount.
 *
 * Classes from the costfactor c of the better direction (BRouter: c > 9998 = not routable, OsmPath):
 *   forbidden  c > 9998 – and still so without the difficulty tags (access bans, via ferrata, one-way …)
 *   too hard   c > 9998 only because of DIFFICULTY_KEYS (sac_scale, mtb:scale, trail_visibility) or highway=via_ferrata / steps
 *              (judged as a path): the router never
 *              uses it either, but the map shows a grey line, not the red "forbidden" (owner 2026-10-22)
 *   good       c <= GOOD[profile]     (thresholds below, docs/map-style-notes.md "Router classes")
 *   possible   everything between ("avoid / poor look like black", owner 2026-10-22)
 * A one-way flag for bikes (cr) where one direction is forbidden or costs at least ONEWAY_EXTRA more than the other.
 * Barrier nodes: BRouter adds the node's initialcost when a route reaches it; >= 1e6 = blocked (OsmPath
 * processTargetNode). The way it arrives on can grant access (nodeaccessgranted: hiking / cycle routes), so a node is
 * blocked for a profile only when it blocks arriving on every way through it.
 */
public class WayClass {
  static final String[] PROFILES = {"hiking-mountain", "trekking", "touring", "mtb"};
  /** Bump when a threshold or the evaluation changes: it is part of the profile hash. */
  static final String VERSION = "wayclass-5";
  /**
   * Good = at most this costfactor (better direction). Chosen from the length-weighted costs of the test region's
   * ways (docs/map-style-notes.md "Router classes"). The map draws green only on path-like ways (paths, footways,
   * cycleways, pedestrian zones – never tracks or streets), so a threshold only has to separate those:
   *   hike      1.40: every walkable path / footway / cycleway the profile does not penalise (off a hiking route 1.39,
   *                   on one ≈ 1.16, pedestrian 1.2); foot=no (+4 → 6.2), bad trail visibility (+1.5), informal,
   *                   fords and steps (3.6) are "possible".
   *   trekking  1.20: cycleways and bicycle=yes|designated paths / footways (1.15 off a cycle route, 1.0 on one);
   *                   residential 1.25, untagged paths 5.15 (pushing); compacted counts as firm (2026-10-26). Was 1.10 while the off-route add was 0.05
   *                   (trekking.brf 0.15 since 2026-10-25).
   *   touring   1.20: as trekking (touring.brf = trekking.brf with other surface / traffic weights, 2026-10-25;
   *                   before: fastbike, 2.00).
   *   MTB       7.00: unpaved cycleways and bicycle=yes|designated paths (6.67), easy trails; plain paths 10.05, paved
   *                   cycleways 9.6 (mtb.brf path_preference 20), streets 13+.
   */
  static final float[] GOOD = {1.40f, 1.20f, 1.20f, 7.0f};
  static final float FORBIDDEN = 9998f;
  /** the bike profiles add +100 to the costfactor against a one-way (trekking / touring / mtb .brf onewaypenalty) */
  static final float ONEWAY_EXTRA = 50f;
  static final float NODE_BLOCKED = 1e6f;

  final BExpressionContextWay[] ways = new BExpressionContextWay[PROFILES.length];
  final BExpressionContextNode[] nodes = new BExpressionContextNode[PROFILES.length];
  /** per way id: nodeaccessgranted per profile (for the barrier nodes) */
  final Map<String, boolean[]> granted = new HashMap<>();
  /** cache: request tags (sorted) → answer columns */
  final Map<String, String> wayCache = new HashMap<>();
  final Map<String, boolean[]> grantCache = new HashMap<>();
  final Map<String, float[]> nodeCache = new HashMap<>();
  final boolean costs;

  WayClass(File dir, boolean costs) throws Exception {
    this.costs = costs;
    for (int p = 0; p < PROFILES.length; p++) {
      BExpressionMetaData meta = new BExpressionMetaData();
      BExpressionContextWay w = new BExpressionContextWay(meta);
      BExpressionContextNode n = new BExpressionContextNode(meta);
      meta.readMetaData(new File(dir, "lookups.dat"));
      Map<String, String> kv = new HashMap<>();
      if (p == 0) { kv.put("SAC_scale_limit", "6"); kv.put("SAC_scale_preferred", "3"); }
      File brf = new File(dir, PROFILES[p] + ".brf");
      w.parseFile(brf, "global", new HashMap<>(kv));
      n.parseFile(brf, "global", new HashMap<>(kv));
      ways[p] = w;
      nodes[p] = n;
    }
  }

  static String hash(File dir) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    md.update(VERSION.getBytes(StandardCharsets.UTF_8));
    md.update(Arrays.toString(GOOD).getBytes(StandardCharsets.UTF_8));
    for (String f : new String[]{"lookups.dat", "hiking-mountain.brf", "trekking.brf", "touring.brf", "mtb.brf"}) {
      md.update(f.getBytes(StandardCharsets.UTF_8));
      md.update(Files.readAllBytes(new File(dir, f).toPath()));
    }
    StringBuilder sb = new StringBuilder();
    for (byte b : md.digest()) sb.append(String.format("%02x", b));
    return sb.substring(0, 16);
  }

  /** lookup data of one profile's way context; the hike evaluation leaves sac_scale / mtb:scale out */
  byte[] encodeWay(int p, List<String[]> tags) {
    BExpressionContextWay ctx = ways[p];
    int[] ld = ctx.createNewLookupData();
    for (String[] kv : tags) {
      if (p == 0 && (kv[0].equals("sac_scale") || kv[0].equals("mtb:scale"))) continue;
      ctx.addLookupValue(kv[0], kv[1], ld);
    }
    return ctx.encode(ld);
  }

  /** Tags that make a way too hard rather than not allowed (class 3 instead of 2). */
  static final List<String> DIFFICULTY_KEYS = List.of("sac_scale", "mtb:scale", "mtb:scale:uphill", "trail_visibility");

  /** forbidden only because of its difficulty: allowed once the difficulty tags are left out */
  boolean tooHard(int p, List<String[]> tags) {
    List<String[]> rest = new ArrayList<>();
    boolean changed = false;
    for (String[] kv : tags) {
      if (DIFFICULTY_KEYS.contains(kv[0])) { changed = true; continue; }
      // a via ferrata is a (very) hard trail, not a ban (owner 2026-10-22): judged like a path without the grade
      // steps: carrying the bike is hard, not banned (owner 2026-10-22: MTB steps grey, not red)
      if (kv[0].equals("highway") && (kv[1].equals("via_ferrata") || kv[1].equals("steps"))) {
        rest.add(new String[]{"highway", "path"}); changed = true; continue;
      }
      rest.add(kv);
    }
    if (!changed) return false;
    BExpressionContextWay ctx = ways[p];
    byte[] d = encodeWay(p, rest);
    ctx.evaluate(false, d); float f = ctx.getCostfactor();
    ctx.evaluate(true, d); float r = ctx.getCostfactor();
    return Math.min(f, r) <= FORBIDDEN;
  }

  static int classOf(float c, int p) {
    if (c > FORBIDDEN) return 2;
    return c <= GOOD[p] ? 0 : 1;
  }

  /** answer columns of a way: ch ct cf cm cr [costs] */
  String way(String id, List<String[]> tags) {
    String key = key(tags);
    String hit = wayCache.get(key);
    if (hit != null) {
      granted.put(id, grantCache.get(key));
      return hit;
    }
    StringBuilder sb = new StringBuilder();
    StringBuilder raw = new StringBuilder();
    boolean[] grant = new boolean[PROFILES.length];
    int cr = 0;
    for (int p = 0; p < PROFILES.length; p++) {
      BExpressionContextWay ctx = ways[p];
      byte[] d = encodeWay(p, tags);
      ctx.evaluate(false, d);
      float f = ctx.getCostfactor();
      grant[p] = ctx.getNodeAccessGranted() != 0f;
      ctx.evaluate(true, d);
      float r = ctx.getCostfactor();
      int cls = classOf(Math.min(f, r), p);
      if (cls == 2 && tooHard(p, tags)) cls = 3;
      sb.append('\t').append(cls);
      if (p == 1) cr = oneway(f, r);  // trekking: the bike one-way rules are the same in all bike profiles (selftest)
      raw.append(String.format("\t%.2f/%.2f", f, r));
    }
    sb.append('\t').append(cr);
    if (costs) sb.append(raw);
    String out = sb.toString();
    wayCache.put(key, out);
    grantCache.put(key, grant);
    granted.put(id, grant);
    return out;
  }

  static int oneway(float f, float r) {
    boolean fBad = f > FORBIDDEN, rBad = r > FORBIDDEN;
    if (fBad && rBad) return 0;
    if (rBad || r - f >= ONEWAY_EXTRA) return 1;
    if (fBad || f - r >= ONEWAY_EXTRA) return -1;
    return 0;
  }

  /** blocked-profile mask of a barrier node arriving on the given ways */
  int node(List<String[]> tags, List<String> wayIds) {
    String key = key(tags);
    float[] cost = nodeCache.get(key);
    if (cost == null) {
      cost = new float[PROFILES.length * 2];  // [p*2] = without, [p*2+1] = with node access granted
      for (int p = 0; p < PROFILES.length; p++) {
        BExpressionContextNode ctx = nodes[p];
        int[] ld = ctx.createNewLookupData();
        for (String[] kv : tags) ctx.addLookupValue(kv[0], kv[1], ld);
        byte[] d = ctx.encode(ld);
        ctx.evaluate(false, d);
        cost[p * 2] = ctx.getInitialcost();
        ctx.evaluate(true, d);
        cost[p * 2 + 1] = ctx.getInitialcost();
      }
      nodeCache.put(key, cost);
    }
    int mask = 0;
    for (int p = 0; p < PROFILES.length; p++) {
      boolean blocked = true;
      for (String w : wayIds.isEmpty() ? List.of("") : wayIds) {
        boolean[] g = granted.get(w);
        boolean gr = g != null && g[p];
        if (cost[p * 2 + (gr ? 1 : 0)] < NODE_BLOCKED) { blocked = false; break; }
      }
      if (blocked) mask |= 1 << p;
    }
    return mask;
  }

  static String key(List<String[]> tags) {
    List<String> s = new ArrayList<>();
    for (String[] kv : tags) s.add(kv[0] + "=" + kv[1]);
    s.sort(null);
    return String.join("\t", s);
  }

  static List<String[]> tags(String[] cols, int from) {
    List<String[]> out = new ArrayList<>();
    for (int i = from; i < cols.length; i++) {
      int eq = cols[i].indexOf('=');
      if (eq > 0) out.add(new String[]{cols[i].substring(0, eq), cols[i].substring(eq + 1)});
    }
    return out;
  }

  static List<String[]> t(String... kv) {
    List<String[]> out = new ArrayList<>();
    for (String s : kv) out.add(s.split("=", 2));
    return out;
  }

  /** Known tag sets (map vs router audit) → expected classes "ch ct cf cm cr". */
  int selftest() {
    Object[][] cases = {
      {t("highway=footway", "access=private"), "2 2 2 2 0"},
      {t("highway=footway", "access=private", "route_hiking_lwn=yes"), "1 2 2 2 0"},
      {t("highway=footway", "access=residents"), "2 2 2 2 0"},
      {t("highway=path"), "0 1 1 1 0"},
      {t("highway=footway"), "0 1 1 1 0"},
      {t("highway=path", "foot=no"), "1 1 1 1 0"},
      {t("highway=path", "route_hiking_lwn=yes"), "0 1 1 1 0"},
      {t("highway=path", "bicycle=designated"), "0 0 0 0 0"},
      {t("highway=path", "bicycle=yes"), "0 0 0 0 0"},  // touring.brf = trekking (fastbike: 1)
      // compacted = firm (2026-10-26, Hilden Itter path): a bike path with it is good; a footway without a bicycle tag
      // stays "possible" (+1 pushing), whatever its surface
      {t("highway=path", "bicycle=designated", "foot=designated", "surface=compacted"), "0 0 0 0 0"},
      {t("highway=footway", "surface=compacted"), "0 1 1 1 0"},
      {t("highway=footway", "bicycle=no", "surface=asphalt"), "0 1 1 1 0"},
      {t("highway=path", "sac_scale=alpine_hiking"), "0 3 3 3 0"},
      {t("highway=path", "sac_scale=demanding_mountain_hiking"), "0 3 3 1 0"},
      {t("highway=path", "sac_scale=alpine_hiking", "access=private"), "2 2 2 2 0"},
      {t("highway=path", "trail_visibility=bad"), "1 1 1 1 0"},
      {t("highway=path", "trail_visibility=no"), "3 1 1 1 0"},
      {t("highway=via_ferrata"), "3 3 3 3 0"},
      {t("highway=via_ferrata", "access=no"), "2 2 2 2 0"},
      {t("highway=steps"), "1 1 1 3 0"},
      {t("highway=steps", "access=private"), "2 2 2 2 0"},
      {t("highway=cycleway"), "0 0 0 0 0"},
      {t("highway=cycleway", "surface=asphalt"), "0 0 0 1 0"},
      {t("highway=cycleway", "oneway=yes"), "0 0 0 0 1"},
      {t("highway=cycleway", "oneway=-1"), "0 0 0 0 -1"},
      {t("highway=residential", "oneway=yes", "oneway:bicycle=no"), "0 1 1 1 0"},
      {t("highway=residential", "access=private"), "2 2 2 2 0"},
      {t("highway=pedestrian"), "0 1 1 1 0"},
      // ferries (2026-10-26): no bikes on board = forbidden for bikes (also on a cycle route), foot=no for hikers
      {t("route=ferry", "foot=yes", "bicycle=yes"), "1 1 1 0 0"},
      {t("route=ferry", "bicycle=dismount"), "1 1 1 1 0"},
      {t("route=ferry", "foot=yes", "bicycle=no"), "1 2 2 2 0"},
      {t("route=ferry", "foot=yes", "bicycle=no", "route_bicycle_lcn=yes"), "1 2 2 2 0"},
      {t("route=ferry", "access=no", "foot=yes"), "1 2 2 2 0"},
      {t("route=ferry", "foot=no", "bicycle=yes"), "2 1 1 0 0"},
    };
    int bad = 0;
    for (Object[] c : cases) {
      @SuppressWarnings("unchecked") List<String[]> tg = (List<String[]>) c[0];
      String got = String.join(" ", Arrays.copyOf(way("selftest", tg).substring(1).split("\t"), 5));
      boolean ok = got.equals(c[1]);
      if (!ok) bad++;
      System.out.printf("%s %-60s %s%s%n", ok ? "ok  " : "FAIL", key(tg).replace('\t', ' '), got, ok ? "" : "   expected " + c[1]);
    }
    // the bike one-way rule must agree in all bike profiles (cr is taken from trekking)
    for (List<String[]> tg : List.of(t("highway=cycleway", "oneway=yes"), t("highway=path", "oneway=yes"),
        t("highway=residential", "oneway=yes"), t("highway=track", "oneway=-1"), t("highway=residential", "oneway=yes", "oneway:bicycle=no"))) {
      int[] dir = new int[3];
      for (int p = 1; p < PROFILES.length; p++) {
        byte[] d = encodeWay(p, tg);
        ways[p].evaluate(false, d); float f = ways[p].getCostfactor();
        ways[p].evaluate(true, d); float r = ways[p].getCostfactor();
        dir[p - 1] = oneway(f, r);
      }
      boolean same = dir[0] == dir[1] && dir[1] == dir[2];
      if (!same) bad++;
      System.out.printf("%s one-way %-50s trekking %d touring %d mtb %d%n", same ? "ok  " : "FAIL", key(tg).replace('\t', ' '), dir[0], dir[1], dir[2]);
    }
    return bad;
  }

  public static void main(String[] a) throws Exception {
    File dir = null;
    boolean costs = false, onlyHash = false, selftest = false;
    for (int i = 0; i < a.length; i++) {
      switch (a[i]) {
        case "--profiles": dir = new File(a[++i]); break;
        case "--costs": costs = true; break;
        case "--hash": onlyHash = true; break;
        case "--selftest": selftest = true; break;
        default: System.err.println("unknown argument " + a[i]); System.exit(2);
      }
    }
    if (dir == null) { System.err.println("usage: WayClass --profiles DIR [--costs|--hash|--selftest]"); System.exit(2); }
    String h = hash(dir);
    if (onlyHash) { System.out.println(h); return; }
    WayClass wc = new WayClass(dir, costs);
    if (selftest) {
      int bad = wc.selftest();
      System.out.println(bad == 0 ? "selftest OK (profiles " + h + ")" : "selftest: " + bad + " wrong");
      System.exit(bad == 0 ? 0 : 1);
    }
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1 << 16);
    BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), 1 << 16);
    out.write("H\t" + h + "\n");
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isEmpty()) continue;
      String[] cols = line.split("\t");
      if (cols[0].equals("W") && cols.length >= 2) {
        out.write("W\t" + cols[1] + wc.way(cols[1], tags(cols, 2)) + "\n");
      } else if (cols[0].equals("N") && cols.length >= 3) {
        List<String> ids = cols[2].equals("-") ? List.of() : Arrays.asList(cols[2].split(","));
        out.write("N\t" + cols[1] + "\t" + wc.node(tags(cols, 3), ids) + "\n");
      } else {
        System.err.println("bad request line: " + line);
        System.exit(2);
      }
    }
    out.flush();
  }
}
