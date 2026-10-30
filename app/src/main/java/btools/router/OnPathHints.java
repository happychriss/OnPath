package btools.router;

/**
 * Reads BRouter's turn instructions ("voice hints", computed at real junctions only) from a finished track. In
 * BRouter's own package because VoiceHint's fields are package-private. Never throws; no hints = empty array.
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
