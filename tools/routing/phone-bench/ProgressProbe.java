import btools.router.OsmNodeNamed;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Can BRouter tell how far a search is (owner 2026-11-05: the wait bar must move within one long leg)? Polls the
 * engine's public getOpenSet() (BRouter's own app draws its search area this way; _findTrack syncs on the open set)
 * every 100 ms during the owner's 98 km Ruhr leg and prints: time, pass (airDistanceCostFactor), open-set size, the
 * frontier node nearest the goal (km) and links processed. Run: javac/java with the brouter jars, args <dir> as
 * PhoneBench (E5_N50.rd5, touring.brf, lookups.dat).
 */
public class ProgressProbe {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        double[] from = {51.1686, 6.9390}, to = {51.7385, 7.5234};
        RoutingContext rc = new RoutingContext();
        rc.localFunction = new File(dir, "touring.brf").getAbsolutePath();
        Map<String, String> kv = new HashMap<>();
        kv.put("turnInstructionMode", "4"); kv.put("correctMisplacedViaPoints", "1");
        kv.put("processUnusedTags", "1"); kv.put("use_dynamic_range", "0"); kv.put("waypointCatchingRange", "1000");
        rc.keyValues = kv;
        List<OsmNodeNamed> nodes = new ArrayList<>();
        for (double[] p : new double[][]{from, to}) {
            OsmNodeNamed n = new OsmNodeNamed();
            n.name = nodes.isEmpty() ? "from" : "to";
            n.ilon = (int) ((p[1] + 180.0) * 1e6 + 0.5); n.ilat = (int) ((p[0] + 90.0) * 1e6 + 0.5);
            nodes.add(n);
        }
        RoutingEngine e = new RoutingEngine(null, null, dir, nodes, rc);
        e.quite = true;
        long t0 = System.nanoTime();
        Thread poll = new Thread(() -> {
            while (!e.isFinished()) {
                int[] os = e.getOpenSet();
                double best = Double.MAX_VALUE;
                for (int i = 0; i + 1 < os.length; i += 2) {
                    double lon = os[i] / 1e6 - 180, lat = os[i + 1] / 1e6 - 90;
                    double dy = (lat - to[0]) * 111.195, dx = (lon - to[1]) * 111.195 * Math.cos(Math.toRadians(lat));
                    best = Math.min(best, Math.sqrt(dx * dx + dy * dy));
                }
                System.out.printf("%6d ms  air %.2f  open %4d  nearest-to-goal %6.1f km  links %d%n", (System.nanoTime() - t0) / 1_000_000,
                    e.airDistanceCostFactor, os.length / 2, best, e.getLinksProcessed());
                try { Thread.sleep(100); } catch (InterruptedException x) { return; }
            }
        });
        poll.setDaemon(true);
        poll.start();
        e.doRun(600_000);
        System.out.printf("done %d ms, %.1f km, error %s%n", (System.nanoTime() - t0) / 1_000_000,
            e.getFoundTrack() == null ? 0.0 : e.getFoundTrack().distance / 1000.0, e.getErrorMessage());
    }
}
