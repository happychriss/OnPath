package btools.router;

import btools.mapaccess.MatchedWaypoint;
import btools.mapaccess.OsmNodePairSet;

/**
 * Reads BRouter's turn instructions ("voice hints", computed at real junctions only) and matched waypoints from a
 * finished track, and reaches the engine's island list for the second snap of an isolated point. In BRouter's own
 * package because VoiceHint's fields are package-private, OsmTrack.matchedWaypoints is protected and
 * RoutingEngine.islandNodePairs is package-private in OnPath's jar – no reflection, so the same code runs in the web
 * planner (TeaVM). Never throws; no hints = empty array.
 */
public final class OnPathHints {
    private OnPathHints() {}

    /** Diagnostics: "hints N" / "no hint list". */
    public static String describe(OsmTrack track) {
        if (track == null) return "no track";
        if (track.voiceHints == null) return "no hint list";
        return "hints " + (track.voiceHints.list == null ? 0 : track.voiceHints.list.size());
    }

    /**
     * One row per hint: { indexInTrack, cmd, angle, roundaboutExit, goodAngle, oldPrio, goodPrio, n, then n times
     * (otherAngle, otherPrio, otherWrongWay, otherCost) }. cmd = BRouter's VoiceHint command (TL=2, TR=5, …);
     * roundaboutExit = the exit to take in a roundabout (1, 2, …; negative in left-hand roundabouts; 0 = none). The rest
     * describes the junction for the bike's "could one go wrong here" check (com.onpath.app.routing.JunctionShape):
     * goodAngle = turn angle of the way the route takes at this node (degrees, > 0 right; [angle] may also carry bends
     * before it), the priority class of the way the route comes on / goes on (profile's priorityclassifier: 26 primary …
     * 6 residential / cycleway, 4 path / track), and the other ways at the node with their angle, class, 1 = a oneway
     * against us (else 0) and the profile's cost factor (≥ 10000 = no access). A hint without way data has the first four
     * columns only.
     */
    public static double[][] of(OsmTrack track) {
        try {
            VoiceHintList l = track == null ? null : track.voiceHints;
            if (l == null || l.list == null) return new double[0][];
            double[][] out = new double[l.list.size()][];
            for (int i = 0; i < l.list.size(); i++) {
                VoiceHint h = l.list.get(i);
                out[i] = row(h);
            }
            return out;
        } catch (RuntimeException e) {
            // expected: hints are optional – an unreadable list means a route without junction cues
            return new double[0][];
        }
    }

    /**
     * Where BRouter matched each input point on a way (its crosspoint), by the names the app gave the points ("from",
     * "via1", …, "to"): one { lat, lon } row per name, null where unknown (no list, no crosspoint, name not found).
     */
    public static double[][] matched(OsmTrack track, String[] names) {
        int n = names == null ? 0 : names.length;
        double[][] out = new double[n][];
        try {
            java.util.List<MatchedWaypoint> list = track == null ? null : track.matchedWaypoints;
            if (list == null) return out;
            for (MatchedWaypoint m : list) {
                if (m == null || m.name == null || m.crosspoint == null) continue;
                for (int i = 0; i < n; i++) {
                    if (out[i] == null && m.name.equals(names[i])) {
                        out[i] = new double[] { m.crosspoint.ilat / 1_000_000.0 - 90.0, m.crosspoint.ilon / 1_000_000.0 - 180.0 };
                    }
                }
            }
        } catch (RuntimeException e) {
            // expected: matched points are optional – unknown means the route geometry is used instead
        }
        return out;
    }

    /**
     * The way (BRouter ids of its two end nodes) that a run of [engine] snapped the point [name] to – also after a
     * failed run (island) – or null. RoutingEngine.matchedWaypoints is protected: read here, no reflection.
     */
    public static long[] snappedWay(RoutingEngine engine, String name) {
        try {
            java.util.List<MatchedWaypoint> list = engine == null ? null : engine.matchedWaypoints;
            if (list == null || name == null) return null;
            for (MatchedWaypoint m : list) {
                if (m != null && name.equals(m.name) && m.node1 != null && m.node2 != null) {
                    return new long[] { m.node1.getIdFromPos(), m.node2.getIdFromPos() };
                }
            }
        } catch (RuntimeException e) {
            // expected: unknown means no second snap for an isolated way (the caller logs it)
        }
        return null;
    }

    /**
     * How often BRouter itself retried after an island in a run of [engine] (the freeze count of its island list), or
     * -1 when unknown. RoutingEngine.islandNodePairs is private upstream; OnPath's brouter-core jar has it
     * package-private (tools/routing/patch_brouter_jar.py) so it is read here – no reflection, the web (TeaVM) too.
     */
    public static int islandRetries(RoutingEngine engine) {
        try {
            OsmNodePairSet pairs = engine == null ? null : engine.islandNodePairs;
            return pairs == null ? -1 : pairs.getFreezeCount();
        } catch (RuntimeException e) {
            // expected: unknown means "no track found" is not taken for an island start (the caller logs it)
            return -1;
        }
    }

    /**
     * Makes the waypoint matching of a not yet run [engine] skip [ways] (each { node1, node2 } as BRouter ids): they go
     * into its island list – used only when snapping points – as BRouter's own island retry does. False = not done.
     */
    public static boolean skipOnSnap(RoutingEngine engine, long[][] ways) {
        try {
            OsmNodePairSet pairs = engine == null ? null : engine.islandNodePairs;
            if (pairs == null || ways == null) return false;
            for (long[] w : ways) pairs.addTempPair(w[0], w[1]);
            pairs.freezeTempPairs();
            return true;
        } catch (RuntimeException e) {
            // expected: not done means the point stays on the isolated way (the caller logs it)
            return false;
        }
    }

    private static double[] row(VoiceHint h) {
        double[] base = { h.indexInTrack, h.cmd, h.angle, h.roundaboutExit };
        try {
            if (h.goodWay == null || h.oldWay == null) return base;
            int n = h.badWays == null ? 0 : h.badWays.size();
            double[] r = new double[8 + 4 * n];
            System.arraycopy(base, 0, r, 0, 4);
            r[4] = h.goodWay.turnangle;
            r[5] = h.oldWay.getPrio();
            r[6] = h.goodWay.getPrio();
            r[7] = n;
            for (int k = 0; k < n; k++) {
                MessageData b = h.badWays.get(k);
                r[8 + 4 * k] = b.turnangle;
                r[9 + 4 * k] = b.getPrio();
                r[10 + 4 * k] = b.isBadOneway() ? 1 : 0;
                r[11 + 4 * k] = b.costfactor;
            }
            return r;
        } catch (RuntimeException e) {
            // expected: the branch data is optional – the hint itself still counts (the cue then falls back)
            return base;
        }
    }
}
