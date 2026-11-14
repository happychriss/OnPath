"""Level 3 – what this person wants. One scale, gemütlich … wild, from the activity and (for hikes) the T level:
T1 is relaxed and likes a made path, T5 wants the wilderness; a touring cyclist wants smooth and calm, gravel wants
further out, MTB wants trails. The page's few controls only nudge it. Pure: wish + settings -> Character.
"""


def wish(b, S):
    """The page's / the planner's controls -> a complete wish. Unknown or missing values fall back to the middle.
    Time and ascent are ranges: `hours` (or `duration`) = [from, to] hours, `ascent` = [from, to] metres up. A single
    number of hours is widened to the usual band; no ascent = the range that fits the character and the effort."""
    act = b.get("activity") if b.get("activity") in S["activities"] and b.get("activity") != "_about" else "hike"
    A = S["activities"][act]
    pace = b.get("pace", "normal")
    pace = float(A["pace"].get(pace, A["pace"]["normal"]) if isinstance(pace, str) else pace)
    word = lambda k, table, default: b.get(k) if b.get(k) in table else default
    h = b.get("duration") or b.get("hours") or 2
    if isinstance(h, (list, tuple)):
        hours = sorted(max(0.25, min(10.0, float(x))) for x in h[:2])
    else:
        band = S["family"][A["family"]]["band_km"] / pace
        hours = [max(0.25, float(h) - band), min(10.0, float(h) + band)]
    up = b.get("ascent")
    return {"start": [float(b["start"][0]), float(b["start"][1])], "start_name": str(b.get("start_name", ""))[:80],
            "activity": act, "t_level": max(1, min(5, int(b.get("t_level") or 2))), "hours": hours, "pace": pace,
            "ascent": sorted(max(0.0, float(x)) for x in up[:2]) if isinstance(up, (list, tuple)) and len(up) == 2 else None,
            "effort": word("effort", S["controls"]["effort"], "mittel"),
            "natur": word("natur", S["controls"]["level"], "mittel"), "ruhe": word("ruhe", S["controls"]["level"], "mittel"),
            "bekannt": word("bekannt", S["controls"]["level"], "mittel"), "wasser": bool(b.get("wasser")),
            "surface": word("surface", S["controls"]["surface"], "gemischt"), "seed": int(b.get("seed") or 1),
            "tips": bool(b.get("tips") or b.get("ai"))}


class Character:
    def __init__(self, w, S):
        A, C, K = S["activities"][w["activity"]], S["character"], S["controls"]
        F = S["family"][A["family"]]
        self.family, self.bike, self.profile, self.pace = F, A["family"] == "wheel", A["profile"], w["pace"]
        self.wild = c = A["wild"][w["t_level"] - 1] if isinstance(A["wild"], list) else A["wild"]
        mix = lambda a, b, t=c: a + (b - a) * max(0.0, min(1.0, t))
        ends = C["gemütlich"], C["wild"]
        axes = {k: mix(ends[0]["axes"][k], ends[1]["axes"][k]) for k in ends[0]["axes"]}
        axes["natur"] *= K["level"][w["natur"]]
        axes["ruhe"] *= K["level"][w["ruhe"]]
        self.water = w["wasser"]
        if self.water:
            axes["besonderes"] *= K["water"]
        total = sum(axes.values())
        self.axes = {k: v / total for k, v in axes.items()}
        self.depth = mix(ends[0]["depth"], ends[1]["depth"])
        self.signed = min(1.0, mix(ends[0]["signed"], ends[1]["signed"]) * K["level"][w["bekannt"]])
        g = F["ground"]
        self.ground = [mix(a, b, c + K["surface"][w["surface"]]) for a, b in zip(g["gemütlich"], g["wild"])]
        # the two ranges: time as wished; ascent as wished, else what fits this character and the chosen effort
        self.hours = tuple(w["hours"])
        self.min_m, self.max_m = (h * self.pace * 1000.0 for h in self.hours)
        self.target_m = 0.5 * (self.min_m + self.max_m)
        usual, effort = mix(*F["up_m_per_h"]), K["effort"][w["effort"]]
        self.up = tuple(w["ascent"] or (round(effort[0] * usual * self.hours[0], -1), round(effort[1] * usual * self.hours[1], -1)))
        self.kv = ({"consider_traffic": 1} if self.bike else
                   {"SAC_scale_limit": w["t_level"], "SAC_scale_preferred": min(w["t_level"], 3)})
        self.kv["mainroad_penalty"] = 3.0

    def time(self, metres, up):
        return metres / 1000.0 / self.pace + up / self.family["ascent_m_per_h"]

    def word(self):
        return "gemütlich" if self.wild < 0.25 else "naturnah" if self.wild < 0.5 else "abgelegen" if self.wild < 0.75 else "wild"
