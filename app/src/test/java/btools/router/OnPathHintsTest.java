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

    @Test public void noTrackOrNoListIsUnknown() {
        assertEquals(2, OnPathHints.matched(null, new String[] { "from", "to" }).length);
        assertNull(OnPathHints.matched(null, new String[] { "from", "to" })[0]);
        assertNull(OnPathHints.matched(new OsmTrack(), new String[] { "from", "to" })[1]);
    }
}
