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
     * One row per hint: { indexInTrack, cmd, angle, roundaboutExit }. cmd = BRouter's VoiceHint command (TL=2, TR=5, …);
     * roundaboutExit = the exit to take in a roundabout (1, 2, …; negative in left-hand roundabouts; 0 = none).
     */
    public static double[][] of(OsmTrack track) {
        try {
            VoiceHintList l = track == null ? null : track.voiceHints;
            if (l == null || l.list == null) return new double[0][];
            double[][] out = new double[l.list.size()][];
            for (int i = 0; i < l.list.size(); i++) {
                VoiceHint h = l.list.get(i);
                out[i] = new double[] { h.indexInTrack, h.cmd, h.angle, h.roundaboutExit };
            }
            return out;
        } catch (RuntimeException e) {
            return new double[0][];
        }
    }
}
