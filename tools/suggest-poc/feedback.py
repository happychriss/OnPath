#!/usr/bin/env python3
"""Tour-suggestions POC – the owner's comments from the page, each with the route it belongs to.

  python3 tools/suggest-poc/feedback.py            # all comments, oldest first
  python3 tools/suggest-poc/feedback.py --last 5   # the newest five

The page saves one line per comment to ~/onpath-suggest-poc/feedback.jsonl (run, candidate id, name, numbers, wish,
comment). Older lines (verdict "beste" / "schlecht" + reason, without the route's numbers) are read too – their
numbers come from the saved run.
"""
import json
import os
import sys

import suggest as S


def line(d):
    run, cid = d.get("run"), d.get("candidate")
    text = d.get("comment") or d.get("reason") or ""
    w, n, name, score, place, method = d.get("wish"), d.get("numbers"), d.get("name"), d.get("score"), d.get("place"), d.get("method")
    p = os.path.join(S.DATA, "runs", "%s.json" % run)
    if (n is None or w is None or name is None) and os.path.isfile(p):
        with open(p, encoding="utf-8") as f:
            R = json.load(f)
        w = w or R["wish"]
        method = method or R.get("method")
        ch = [x for x in R["candidates"] if x["chosen"]]
        c = next((x for x in R["candidates"] if x["id"] == cid), None)
        if c:
            n, name, score = n or c["numbers"], name or c["name"], score if score is not None else c["score"]
            place = place or (ch.index(c) + 1 if c in ch else 0)
    L = ["%s · Lauf %s · Runde %s%s" % (d.get("at", "?"), run, cid, " (Platz %d)" % place if place else "")]
    if w:
        sm = w.get("simple") or {}
        L.append("   Wunsch: %s · %s · %s h · %s · Natur %s · Ruhe %s · Bekannte Wege %s%s%s · Verfahren %s" % (
            w.get("start_name") or w.get("start"), w.get("activity", "hike"), w.get("hours"), w.get("surface") or "",
            sm.get("natur", "?"), sm.get("ruhe", "?"), sm.get("bekannt", "?"), " · am Wasser" if sm.get("wasser") else "",
            " · mit KI" if w.get("ai") else "", (method or "a").upper()))
    if n:
        L.append("   Runde: %s · %.1f km · %s h · %s Punkte" % (name, n["km"], S.fmt_h(n["hours"]), ("%.0f" % score) if score is not None else "?"))
        L.append("   Zahlen: am Wald %s %% · Straße %s %% · an / neben Straße längstes Stück %s m%s · markiert %s %% · doppelt %s %%%s%s%s" % (
            n.get("forest_pct"), n.get("street_pct"), n.get("main_longest_m"),
            " (%s %%)" % n["roadside_pct"] if "roadside_pct" in n else "", n.get("signed_pct"), n.get("doubled_pct"),
            " · kreuzt sich %d×" % n["self_cross"] if "self_cross" in n else "",
            " · durch Häuser %s %%" % n["built_pct"] if "built_pct" in n else "",
            " · tief in der Natur %s %%" % n["deep_pct"] if "deep_pct" in n else ""))
        if S.goals_text(n):
            L.append("   Am Weg: " + S.goals_text(n))
    else:
        L.append("   (Lauf nicht mehr gespeichert – keine Zahlen)")
    if d.get("verdict"):
        L.append("   Urteil (alte Form): " + d["verdict"])
    L.append("   » " + text)
    return "\n".join(L)


if __name__ == "__main__":
    p = os.path.join(S.DATA, "feedback.jsonl")
    if not os.path.isfile(p):
        sys.exit("noch keine Kommentare: " + p)
    with open(p, encoding="utf-8") as f:
        rows = [json.loads(x) for x in f if x.strip()]
    if "--last" in sys.argv:
        rows = rows[-int(sys.argv[sys.argv.index("--last") + 1]):]
    print("\n\n".join(line(d) for d in rows))
    print("\n%d Kommentare · %s" % (len(rows), p))
