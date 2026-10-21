import btools.expressions.*;
import java.io.File;
import java.util.*;

/** Evaluate a BRouter profile's way context for given tag sets – no routing, no segments. */
public class EvalWay {
  public static void main(String[] a) throws Exception {
    String dir = a[0];
    String[] profiles = {"hiking-mountain", "trekking", "fastbike-verylowtraffic", "mtb"};
    String[][] cases = {
      {"highway=footway"},
      {"highway=footway","access=private"},
      {"highway=footway","access=private","route_hiking_lwn=yes"},
      {"highway=footway","access=residents"},
      {"highway=footway","foot=private"},
      {"highway=path","foot=no"},
      {"highway=path","access=destination"},
      {"highway=path","access=no","foot=yes"},
      {"highway=path"},
      {"highway=path","sac_scale=mountain_hiking"},
      {"highway=path","sac_scale=demanding_mountain_hiking"},
      {"highway=path","sac_scale=alpine_hiking"},
      {"highway=path","trail_visibility=no"},
      {"highway=path","bicycle=yes"},
      {"highway=path","bicycle=designated"},
      {"highway=path","bicycle=no"},
      {"highway=cycleway"},
      {"highway=cycleway","surface=asphalt"},
      {"highway=cycleway","access=private"},
      {"highway=cycleway","oneway=yes"},
      {"highway=steps"},
      {"highway=via_ferrata"},
      {"highway=track"},
      {"highway=track","tracktype=grade3"},
      {"highway=residential","access=private"},
      {"highway=service","service=driveway","access=private"},
      {"highway=residential","oneway=yes"},
      {"highway=residential","oneway=yes","oneway:bicycle=no"},
      {"highway=pedestrian","bicycle=yes"},
      {"highway=path","vehicle=no"},
    };
    for (String p : profiles) {
      BExpressionMetaData meta = new BExpressionMetaData();
      BExpressionContextWay ctx = new BExpressionContextWay(meta);
      meta.readMetaData(new File(dir, "lookups.dat"));
      Map<String,String> kv = new HashMap<>();
      if (p.startsWith("hiking")) { kv.put("SAC_scale_limit","3"); kv.put("SAC_scale_preferred","3"); }
      ctx.parseFile(new File(dir, p + ".brf"), "global", kv);
      System.out.println("== " + p + "   (costfactor fwd / rev; >=9999 = forbidden)");
      for (String[] c : cases) {
        int[] ld = ctx.createNewLookupData();
        for (String t : c) { String[] kvp = t.split("=", 2); ctx.addLookupValue(kvp[0], kvp[1], ld); }
        byte[] desc = ctx.encode(ld);
        ctx.evaluate(false, desc); float f = ctx.getCostfactor();
        ctx.evaluate(true, desc); float r = ctx.getCostfactor();
        System.out.printf("  %-60s %9.2f %9.2f%n", String.join(" ", c), f, r);
      }
    }
  }
}
