package btools.router;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNode;
import java.util.ArrayList;
import org.junit.Test;

/**
 * BRouter's matched points read without reflection (web planner finding 2: TeaVM has no reflection, so the browser got
 * no matched points and never re-routed to a stop on a way that BRouter cut out – field bug 2026-10-30).
 */
public class OnPathHintsTest {
    private static MatchedWaypoint wp(String name, double lat, double lon) {
        MatchedWaypoint m = new MatchedWaypoint();
        m.name = name;
        m.crosspoint = new OsmNode((int) Math.round((lon + 180.0) * 1_000_000.0), (int) Math.round((lat + 90.0) * 1_000_000.0));
        return m;
    }

    @Test public void matchedPointsByName() {
        OsmTrack track = new OsmTrack();
        track.matchedWaypoints = new ArrayList<>();
        track.matchedWaypoints.add(wp("to", 51.484688, 7.412369));
        track.matchedWaypoints.add(wp("from", 51.492765, 7.41773));
        track.matchedWaypoints.add(wp("via2", 51.486654, 7.416909));
        MatchedWaypoint noCross = new MatchedWaypoint();
        noCross.name = "via1";
        track.matchedWaypoints.add(noCross);
        double[][] m = OnPathHints.matched(track, new String[] { "from", "via1", "via2", "to" });
        assertEquals(4, m.length);
        assertArrayEquals(new double[] { 51.492765, 7.41773 }, m[0], 1e-9);
        assertNull("no crosspoint = unknown", m[1]);
        assertArrayEquals(new double[] { 51.486654, 7.416909 }, m[2], 1e-9);
        assertArrayEquals(new double[] { 51.484688, 7.412369 }, m[3], 1e-9);
    }

    /** The way an island point was snapped to (Köln Dom, IslandTest) – read without reflection too. */
    @Test public void snappedWayByName() {
        RoutingContext rc = new RoutingContext();
        // the constructor parses the profile (next to lookups.dat); the engine is never run here
        rc.localFunction = new java.io.File("src/main/assets/brouter/touring.brf").getAbsolutePath();
        RoutingEngine engine = new RoutingEngine(null, null, null, new ArrayList<>(), rc);
        engine.matchedWaypoints = new ArrayList<>();
        MatchedWaypoint m = wp("via1", 50.9413, 6.9583);
        m.node1 = new OsmNode(1000, 2000);
        m.node2 = new OsmNode(3000, 4000);
        engine.matchedWaypoints.add(wp("from", 50.936, 6.9475));
        engine.matchedWaypoints.add(m);
        assertArrayEquals(new long[] { m.node1.getIdFromPos(), m.node2.getIdFromPos() }, OnPathHints.snappedWay(engine, "via1"));
        assertNull("no way nodes", OnPathHints.snappedWay(engine, "from"));
        assertNull(OnPathHints.snappedWay(engine, "to"));
        assertNull(OnPathHints.snappedWay(null, "via1"));
    }

    /**
     * The island list (RoutingEngine.islandNodePairs, package-private in OnPath's patched brouter-core jar) read and
     * written without reflection, so the browser gets the second snap for an island point like the app (Köln Dom).
     */
    @Test public void islandListWithoutReflection() {
        RoutingContext rc = new RoutingContext();
        rc.localFunction = new java.io.File("src/main/assets/brouter/touring.brf").getAbsolutePath();
        RoutingEngine engine = new RoutingEngine(null, null, null, new ArrayList<>(), rc);
        assertEquals("a fresh engine has not retried", 0, OnPathHints.islandRetries(engine));
        long a = new OsmNode(1000, 2000).getIdFromPos(), b = new OsmNode(3000, 4000).getIdFromPos();
        assertEquals(true, OnPathHints.skipOnSnap(engine, new long[][] { { a, b } }));
        assertEquals("skipped ways are frozen like BRouter's own retry", 1, OnPathHints.islandRetries(engine));
        assertEquals(true, engine.islandNodePairs.hasPair(a, b));
        assertEquals(false, engine.islandNodePairs.hasPair(a, a));
        assertEquals(-1, OnPathHints.islandRetries(null));
        assertEquals(false, OnPathHints.skipOnSnap(null, new long[][] { { a, b } }));
    }

    @Test public void noTrackOrNoListIsUnknown() {
        assertEquals(2, OnPathHints.matched(null, new String[] { "from", "to" }).length);
        assertNull(OnPathHints.matched(null, new String[] { "from", "to" })[0]);
        assertNull(OnPathHints.matched(new OsmTrack(), new String[] { "from", "to" })[1]);
    }
}
