// Reference run of the spike's routes on today's Java engine (app/libs/brouter-*.jar), same output format as
// src/commonMain/kotlin/spike/Main.kt. In BRouter's package: the voice hint fields are package-private.
// Usage (ref/run_ref.sh): RefMain <segmentsDir> <profilesDir> <routes.txt> <outDump> <timesFile> <reps>
package btools.router;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RefMain {
  public static void main(String[] a) throws Exception {
    File seg = new File(a[0]);
    String profiles = a[1];
    List<String> lines = Files.readAllLines(new File(a[2]).toPath());
    int reps = Integer.parseInt(a[5]);
    StringBuilder dump = new StringBuilder();
    StringBuilder times = new StringBuilder();
    long tAll = System.nanoTime();
    for (String line : lines) {
      line = line.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      String[] p = line.split("\\|");
      String name = p[0], profile = p[1];
      times.append(name);
      for (int r = 0; r < reps; r++) {
        RoutingContext rc = new RoutingContext();
        rc.localFunction = new File(profiles, profile + ".brf").getAbsolutePath();
        Map<String, String> kv = new LinkedHashMap<>();
        for (String e : p[3].split(",")) { String[] x = e.split("="); kv.put(x[0], x[1]); }
        rc.keyValues = kv;
        List<OsmNodeNamed> wps = new ArrayList<>();
        String[] pts = p[2].split(";");
        for (int i = 0; i < pts.length; i++) {
          String[] ll = pts[i].split(",");
          OsmNodeNamed n = new OsmNodeNamed();
          n.name = i == 0 ? "from" : i == pts.length - 1 ? "to" : "via" + i;
          n.ilon = (int) ((Double.parseDouble(ll[0]) + 180.0) * 1_000_000.0 + 0.5);
          n.ilat = (int) ((Double.parseDouble(ll[1]) + 90.0) * 1_000_000.0 + 0.5);
          wps.add(n);
        }
        long t0 = System.nanoTime();
        RoutingEngine re = new RoutingEngine(null, null, seg, wps, rc);
        re.quite = true;
        re.doRun(0);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        times.append(' ').append(ms);
        if (r == 0) dump(dump, name, profile, re);
      }
      times.append('\n');
    }
    times.append("total_ms ").append((System.nanoTime() - tAll) / 1_000_000L).append('\n');
    for (String l : Files.readAllLines(new File("/proc/self/status").toPath())) {
      if (l.startsWith("VmHWM") || l.startsWith("VmRSS")) times.append(l.replaceAll("\\s+", " ")).append('\n');
    }
    try (PrintWriter w = new PrintWriter(a[3], "UTF-8")) { w.print(dump); }
    try (PrintWriter w = new PrintWriter(a[4], "UTF-8")) { w.print(times); }
  }

  private static String hex(float f) { return Integer.toHexString(Float.floatToRawIntBits(f)); }

  private static void dump(StringBuilder sb, String name, String profile, RoutingEngine re) {
    sb.append("R ").append(name).append(' ').append(profile).append('\n');
    if (re.getErrorMessage() != null) { sb.append("E ").append(re.getErrorMessage()).append('\n'); return; }
    OsmTrack t = re.getFoundTrack();
    int hints = t.voiceHints == null ? 0 : t.voiceHints.list.size();
    sb.append("T cost=").append(t.cost).append(" dist=").append(t.distance).append(" ascend=").append(t.ascend)
      .append(" plain=").append(t.plainAscend).append(" energy=").append(t.energy).append(" secs=").append(t.getTotalSeconds())
      .append(" nodes=").append(t.nodes.size()).append(" hints=").append(hints).append('\n');
    for (OsmPathElement n : t.nodes) {
      sb.append("N ").append(n.getILon()).append(' ').append(n.getILat()).append(' ').append(n.getSElev()).append(' ')
        .append(n.cost).append(' ').append(hex(n.getTime())).append(' ').append(hex(n.getEnergy())).append(' ');
      MessageData m = n.message;
      if (m == null) sb.append('-');
      else sb.append(Integer.toHexString((m.wayKeyValues + "|" + m.nodeKeyValues).hashCode())).append(' ').append(m.linkdist)
        .append(' ').append(hex(m.costfactor));
      sb.append('\n');
    }
    if (t.voiceHints != null) for (VoiceHint h : t.voiceHints.list) {
      sb.append("H ").append(h.indexInTrack).append(' ').append(h.cmd).append(' ').append(hex(h.angle)).append(' ')
        .append(h.roundaboutExit).append(' ').append(h.ilon).append(' ').append(h.ilat).append('\n');
    }
  }
}
