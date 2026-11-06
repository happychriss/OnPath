import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BRouter alone, timed on the phone's own runtime (ART) without installing anything into the app (owner 2026-11-05,
 * "long bike routes faster", phase 1): the owner's Ruhr → Nordkirchen bike plan (~98 km), as whole requests and leg by
 * leg, with the app's BRouter settings (BRouterEngine.keyValues for Bike). Plain Java so d8 needs no Kotlin stdlib.
 * Run: tools/routing/phone-bench/run.sh (desktop JVM and/or the Pixel via adb + dalvikvm). Args: <dir> with E5_N50.rd5,
 * touring.brf, lookups.dat; optional memoryclass (MB, default 64 = BRouter's own).
 */
public class PhoneBench {
    static final double[] START = {51.1686, 6.9390}, GOAL = {51.7385, 7.5234};
    static final double[] A = {51.3930, 7.0870}, B = {51.4930, 7.4560}, C = {51.4500, 7.2560};

    static File dir;
    static int memoryclass = 64;

    static double[] route(double[]... pts) {
        RoutingContext rc = new RoutingContext();
        rc.localFunction = new File(dir, "touring.brf").getAbsolutePath();
        Map<String, String> kv = new HashMap<>();
        kv.put("turnInstructionMode", "4");
        kv.put("correctMisplacedViaPoints", "1");
        kv.put("correctMisplacedViaPointsDistance", "400");
        kv.put("processUnusedTags", "1");
        kv.put("use_dynamic_range", "0");
        kv.put("waypointCatchingRange", "1000");
        rc.keyValues = kv;
        rc.memoryclass = memoryclass;
        List<OsmNodeNamed> nodes = new ArrayList<>();
        for (int i = 0; i < pts.length; i++) {
            OsmNodeNamed n = new OsmNodeNamed();
            n.name = i == 0 ? "from" : i == pts.length - 1 ? "to" : "via" + i;
            n.ilon = (int) ((pts[i][1] + 180.0) * 1_000_000.0 + 0.5);
            n.ilat = (int) ((pts[i][0] + 90.0) * 1_000_000.0 + 0.5);
            nodes.add(n);
        }
        RoutingEngine e = new RoutingEngine(null, null, dir, nodes, rc);
        e.quite = true;
        long t0 = System.nanoTime();
        e.doRun(600_000);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        OsmTrack t = e.getFoundTrack();
        if (e.getErrorMessage() != null || t == null) throw new RuntimeException("no route: " + e.getErrorMessage());
        return new double[]{ms, t.distance / 1000.0};
    }

    static String fmt(double[] r) { return String.format("%.0f ms (%.1f km)", r[0], r[1]); }

    public static void main(String[] args) {
        dir = new File(args[0]);
        if (args.length > 1) memoryclass = Integer.parseInt(args[1]);
        Runtime rt = Runtime.getRuntime();
        System.out.println("max heap " + rt.maxMemory() / 1_048_576 + " MB, memoryclass " + memoryclass + ", cores " + rt.availableProcessors());
        System.out.println("whole, 2 pts, cold: " + fmt(route(START, GOAL)));
        System.out.println("whole, 2 pts, again: " + fmt(route(START, GOAL)));
        System.out.println("whole, 3 pts (+A): " + fmt(route(START, A, GOAL)));
        System.out.println("whole, 4 pts (+B): " + fmt(route(START, A, B, GOAL)));
        System.out.println("whole, 5 pts (+C): " + fmt(route(START, A, C, B, GOAL)));
        System.out.println("leg START→A: " + fmt(route(START, A)));
        System.out.println("leg A→GOAL: " + fmt(route(A, GOAL)));
        System.out.println("leg A→B: " + fmt(route(A, B)));
        System.out.println("leg B→GOAL: " + fmt(route(B, GOAL)));
        System.out.println("leg A→C: " + fmt(route(A, C)));
        System.out.println("leg C→B: " + fmt(route(C, B)));
        System.out.println("short leg 10 km (START→Benrath area): " + fmt(route(START, new double[]{51.1650, 6.8700})));
    }
}
