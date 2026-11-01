#!/usr/bin/env python3
"""Merge docs/roles/neu/settings-permissions.proposed.json into .claude/settings.json (owner runs it – a Claude session
may not change its own permissions). Keeps hooks and everything else; allow / deny lists are unioned, order kept.
Usage: python3 tools/dev/apply_permissions.py [--dry]"""
import json, sys, pathlib
root = pathlib.Path(__file__).resolve().parents[2]
live = root / ".claude/settings.json"
prop = root / "docs/roles/neu/settings-permissions.proposed.json"
cfg = json.loads(live.read_text()) if live.exists() else {}
new = json.loads(prop.read_text())["permissions"]
perm = cfg.setdefault("permissions", {})
for key in ("allow", "deny"):
    have = perm.setdefault(key, [])
    for rule in new.get(key, []):
        if rule not in have:
            have.append(rule)
out = json.dumps(cfg, indent=2, ensure_ascii=False) + "\n"
if "--dry" in sys.argv:
    print(out)
else:
    live.write_text(out)
    print(f"{live}: {len(perm['allow'])} allow, {len(perm['deny'])} deny rules")
