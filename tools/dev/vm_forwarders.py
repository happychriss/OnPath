#!/usr/bin/env python3
"""Rename reads of AppViewModel's pass-through forwarders to the slice member (rebuild phase 2, piece 21).

A forwarder is a member of AppViewModel that only hands a call / a property on to a slice:
    fun x(a, b) = slice.y(a, b)          val x: T get() = slice.y          var x: T get() = slice.y; set(v) { slice.y = v }
Only EXACT pass-throughs are renamed (same arguments in the same order, same defaults as the slice's own function; a forward through
another forwarder is followed: `settings.navHeadingUp` -> `settingsSlice.settings.navHeadingUp`); everything else is listed and left
(`table` prints it): overloads, defaults that differ, `suspend fun`, a conversion, a guard, two calls.

Modes (stdlib only, run from anywhere inside the repo; idempotent, driven by the table, no line numbers):
  inventory            every member of the view model by kind, with reader counts (screens / E2E / unit tests / inside the view model)
  table                the forwarder table (the file tools/dev/vm_forwarders.tsv keeps it after the definitions are gone) + what is kept
  dead / delete-dead   forwarders nobody reads (step B); `delete-dead --except=a,b` for names the compiler still finds read
  plan                 what `apply` would rewrite per file; "nothing to do" when there is nothing
  apply [--delete]     rewrite `vm.x` -> `vm.slice.y` (also `vm::x`, `vm?.x`, setters, `this@AppViewModel.x`) in app/src/main,
                       app/src/androidTest and the tests (KDoc / // comments untouched); makes the slices that are read from outside
                       public, writes explicit types on slices (Kotlin: "Type checking has run into a recursive problem" once the
                       forwarders are gone), writes the table file; with --delete also removes the forwarder definitions
                       (except KEEP: their reads are renamed, the definition stays; KEEP_ALL: left alone)
  fix LOGFILE          rewrite the reads the compiler reports as "Unresolved reference 'x'" (`with(vm)`, `it.x` in an E2E
                       `read { }`, the view model's own unqualified reads) – at the positions the log names, never at a position
                       already reached through its slice (a follow-up error of an inference cascade)

After a rebase onto a newer main (other sessions keep writing `vm.x` in screens):
    python3 tools/dev/vm_forwarders.py plan           # what would change
    python3 tools/dev/vm_forwarders.py apply          # the persisted table knows forwarders that are gone from the view model
    tools/dev/gradle_slot.sh -- ./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :state:compileTestKotlin \
        :core:compileTestKotlin :app:compileDebugAndroidTestKotlin -Ponpath.e2e=true --continue > /tmp/c.log 2>&1
    python3 tools/dev/vm_forwarders.py fix /tmp/c.log     # repeat compile + fix until the log has no "Unresolved reference"
By hand then: a local named like a slice that shadows it (`plan: Boolean` -> `this.plan.points`), a named argument whose slice
parameter has another name, a new text pin of a deleted forwarder in a test.
"""
import os
import re
import subprocess
import sys

ROOT = subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True, cwd=os.path.dirname(os.path.abspath(__file__))).strip()
VM = os.path.join(ROOT, "app/src/main/java/com/onpath/app/ui/AppViewModel.kt")
TABLE = os.path.join(ROOT, "tools/dev/vm_forwarders.tsv")
SCAN_DIRS = ["app/src/main", "app/src/androidTest", "app/src/test", "state/src/test", "core/src/test", "state/src/main", "core/src/main"]
RECEIVERS = ["vm", "viewModel", "appVm", "model", r"this@AppViewModel"]

# Forwarders whose reads are renamed everywhere but whose definition stays (name -> why): a guard test that may not change reads them
KEEP = {
    "routesOpen": "read by the NoticeBoard lambdas: ViewModelInitOrderTest follows a name into the slice it reaches, `notices` must not reach slices declared below the init",
    "showSettings": "same: the NoticeBoard lambda (ViewModelInitOrderTest)",
    "mode": "same: the NoticeBoard lambda (ViewModelInitOrderTest)",
    "searchOpen": "same: the NoticeBoard lambda (ViewModelInitOrderTest)",
    "errorCard": "read by the start-up init: ViewModelInitOrderTest would follow `notices` into slices declared below that init",
    "sayError": "same: the start-up init",
}

# Forwarders left completely alone (never renamed, never deleted)
KEEP_ALL = {
    "recalc": "RecalcStartOrderTest finds `init { recalc() }` and its hosts' `this@AppViewModel.recalc()` lines; ViewModelInitOrderTest walks them",
}

MOD = r"(?:(?:private|internal|public|override)\s+)*"


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8") as f:
        f.write(s)


# ---------------------------------------------------------------- the view model: members
class Member:
    def __init__(self, kind, name, start, end, lines):
        self.kind, self.name, self.start, self.end, self.lines = kind, name, start, end, lines  # [start, end] inclusive, 0-based

    @property
    def text(self):
        return "\n".join(self.lines)


DECL = re.compile(r"^    " + MOD + r"(lateinit\s+)?(?:(val|var|fun)\s+(?:<[^>]*>\s*)?(?:[\w<>?]+\.)?(\w+)|(init)\s*\{|(sealed|enum|data|class|object|companion)\b)")


def parse_members(lines):
    """Members at indent 4 of the class (the class body starts after `class AppViewModel`)."""
    body = next(i for i, l in enumerate(lines) if l.startswith("class AppViewModel")) + 1
    starts = []
    for i in range(body, len(lines)):
        l = lines[i]
        m = DECL.match(l)
        if m:
            kind = m.group(2) or m.group(4) or m.group(5)
            name = m.group(3) or kind
            starts.append((i, kind, name))
    members = []
    for k, (i, kind, name) in enumerate(starts):
        limit = starts[k + 1][0] if k + 1 < len(starts) else len(lines) - 1
        end = i
        j = i + 1
        while j < limit:
            l = lines[j]
            if l.strip() == "":
                break
            if re.match(r"^    (/\*\*|/\*|//|@)", l):
                break
            if re.match(r"^    \S", l) and not re.match(r"^    [)}\]]", l):
                break
            end = j
            j += 1
        # the member may continue after a blank only when inside brackets; track depth over the text
        depth = 0
        for jj in range(i, end + 1):
            code = re.sub(r'"(\\.|[^"\\])*"', '""', lines[jj].split("//")[0])
            depth += sum(code.count(c) for c in "({[") - sum(code.count(c) for c in ")}]")
        while depth > 0 and end + 1 < limit:
            end += 1
            code = re.sub(r'"(\\.|[^"\\])*"', '""', lines[end].split("//")[0])
            depth += sum(code.count(c) for c in "({[") - sum(code.count(c) for c in ")}]")
        members.append(Member(kind, name, i, end, lines[i:end + 1]))
    return members


def norm(text):
    out = []
    for l in text.split("\n"):
        # drop a trailing // comment (forwarders carry no strings with //)
        if '"' not in l:
            l = re.sub(r"\s//.*$", "", l)
        out.append(l.strip())
    return re.sub(r"\s+", " ", " ".join(out)).strip()


def split_top(s, sep=","):
    parts, depth, cur = [], 0, ""
    for c in s:
        if c in "(<[{":
            depth += 1
        elif c in ")>]}":
            depth -= 1
        if c == sep and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += c
    if cur.strip():
        parts.append(cur)
    return [p.strip() for p in parts]


def match_paren(s, i):
    """index of the ')' matching the '(' at s[i]"""
    depth = 0
    for j in range(i, len(s)):
        if s[j] == "(":
            depth += 1
        elif s[j] == ")":
            depth -= 1
            if depth == 0:
                return j
    return -1


class Fwd:
    def __init__(self, kind, name, target_recv, target_name, vm_params=None, line=None, member=None, setter=False, private_set=False):
        self.kind, self.name, self.recv, self.target = kind, name, target_recv, target_name
        self.params = vm_params  # list of (name, type, default) for funs
        self.member = member
        self.setter = setter


def parse_forwarder(m):
    """A Fwd when the member is an exact pass-through, else None."""
    t = norm(m.text)
    t = re.sub(r"^" + MOD, "", t)
    if m.kind == "fun":
        mm = re.match(r"^fun (\w+)\(", t)
        if not mm:
            return None
        name = mm.group(1)
        op = mm.end() - 1
        cl = match_paren(t, op)
        if cl < 0:
            return None
        params_s, rest = t[op + 1:cl], t[cl + 1:].strip()
        body = None
        mr = re.match(r"^(?::\s*[^=]+?)?\s*=\s*(.*)$", rest)
        if mr:
            body = mr.group(1)
        else:
            mb = re.match(r"^\{\s*(.*?)\s*;?\s*\}$", rest)  # block with one call and no return type
            if mb:
                body = mb.group(1)
        if body is None:
            return None
        mc = re.match(r"^(\w+(?:\.\w+)*)\.(\w+)\(", body)
        if not mc:
            return None
        op2 = body.index("(", mc.end() - 1)
        cl2 = match_paren(body, op2)
        if cl2 != len(body) - 1:
            return None
        args = split_top(body[op2 + 1:cl2])
        params = []
        for p in split_top(params_s):
            pm = re.match(r"^(?:vararg\s+)?(\w+)\s*:\s*(.+?)(?:\s*=\s*(.+))?$", p)
            if not pm:
                return None
            params.append((pm.group(1), pm.group(2), pm.group(3)))
        if [a for a in args] != [p[0] for p in params]:
            return None
        return Fwd("fun", name, mc.group(1), mc.group(2), params, member=m)
    if m.kind == "val":
        mm = re.match(r"^val (\w+)\s*(?::\s*.+?)?\s*get\(\)\s*=\s*(\w+(?:\.\w+)*)\.(\w+)$", t)
        if mm:
            return Fwd("val", mm.group(1), mm.group(2), mm.group(3), member=m)
        mm = re.match(r"^val (\w+)\s*(?::\s*.+?)?\s*get\(\)\s*=\s*(\w+)$", t)
        if mm:
            return Fwd("val", mm.group(1), None, mm.group(2), member=m)
        return None
    if m.kind == "var":
        mm = re.match(r"^var (\w+)\s*(?::\s*.+?)?\s*get\(\)\s*=\s*(\w+(?:\.\w+)*)\.(\w+)\s*;?\s*(?:private\s+)?set\((\w+)\)\s*\{\s*(\w+(?:\.\w+)*)\.(\w+)\s*=\s*(\w+)\s*\}$", t)
        if mm and mm.group(2) == mm.group(5) and mm.group(3) == mm.group(6) and mm.group(4) == mm.group(7):
            return Fwd("var", mm.group(1), mm.group(2), mm.group(3), member=m, setter=True)
        return None
    return None


# ---------------------------------------------------------------- slice facts
def slice_names(members, fwds):
    """Members that are slices: a `val` that is not itself a forwarder and is not a plain state / value holder."""
    fw = {f.member.start for f in fwds if f}
    out = {}
    for m in members:
        if m.kind == "val" and m.start not in fw:
            out[m.name] = m
    return out


STATE_SRC = None


def all_sources():
    global STATE_SRC
    if STATE_SRC is None:
        STATE_SRC = {}
        for d in ("state/src/main", "app/src/main", "core/src/main"):
            for dp, _, fns in os.walk(os.path.join(ROOT, d)):
                for fn in fns:
                    if fn.endswith(".kt") and fn != "AppViewModel.kt":
                        p = os.path.join(dp, fn)
                        STATE_SRC[p] = read(p)
    return STATE_SRC


def target_defaults(member, nparams):
    """default texts of the declarations `fun member(` in :state / :app (excluding the view model): list of param lists"""
    out = []
    for p, s in all_sources().items():
        for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?" + re.escape(member) + r"\(", s):
            op = m.end() - 1
            cl = match_paren(s, op)
            if cl < 0:
                continue
            ps = []
            for q in split_top(re.sub(r"\s+", " ", s[op + 1:cl])):
                qm = re.match(r"^(?:vararg\s+)?(\w+)\s*:\s*(.+?)(?:\s*=\s*(.+))?$", q)
                if qm:
                    ps.append((qm.group(1), qm.group(2), qm.group(3)))
            if len(ps) == nparams:
                out.append(ps)
    return out


def build_table(lines=None):
    """(table, kept, members, fwds): table = {vmName: (root slice, [path..., member], kind, setter)}; kept = [(name, reason)]."""
    if lines is None:
        lines = read(VM).split("\n")
    members = parse_members(lines)
    fwds = [parse_forwarder(m) for m in members]
    slices = slice_names(members, fwds)
    names = [m.name for m in members]
    byname = {}
    for f in fwds:
        if f:
            byname.setdefault(f.name, f)
    table, kept = {}, []
    pending = [f for f in fwds if f and f.name not in KEEP_ALL]
    kept += [(n, "left alone: " + why) for n, why in KEEP_ALL.items() if n in byname]
    kept += [(n, "definition kept on purpose: " + why) for n, why in KEEP.items() if n in byname]
    # fixed point: a forwarder whose target starts at a slice resolves at once; one that starts at another forwarder
    # (settings.navHeadingUp, legCache.clear, headingUp -> navHeadingUp) resolves after that one
    progress = True
    reasons = {}
    while progress:
        progress = False
        for f in list(pending):
            if names.count(f.name) > 1:
                reasons[f.name] = "overloaded name in the view model"
                pending.remove(f)
                continue
            chain = (f.recv.split(".") if f.recv else []) + [f.target]
            first = chain[0]
            if first in slices and len(chain) >= 2:
                root, path = first, chain[1:]
            elif first in table and first != f.name:
                root, rest = table[first][0], table[first][1]
                root, path = root, rest + chain[1:]
            else:
                continue
            if f.kind == "fun" and any(p[2] is not None for p in f.params):
                # same defaults as the slice's own function (only a direct slice member can be compared)
                cands = target_defaults(path[-1], len(f.params))
                if not any(all(a[2] == b[2] for a, b in zip(f.params, ps)) for ps in cands):
                    reasons[f.name] = "default arguments differ or the slice member was not found"
                    pending.remove(f)
                    continue
            table[f.name] = (root, path, f.kind, f.setter)
            pending.remove(f)
            progress = True
    for n, why in reasons.items():
        kept.append((n, why))
    for f in pending:
        if True:
            kept.append((f.name, "forwards to %s.%s, a real member of the view model, not a slice" % (f.recv, f.target) if f.recv else "forwards to a real member (%s)" % f.target))
    return table, kept, members, fwds


def load_persisted():
    t = {}
    if os.path.exists(TABLE):
        for l in read(TABLE).split("\n"):
            if l and not l.startswith("#") and l.split("\t")[0] not in KEEP_ALL:
                a = l.split("\t")
                t[a[0]] = (a[1], a[2].split("."), a[3], a[4] == "1")
    return t


def save_persisted(table):
    rows = ["# vm_forwarders.tsv: forwarder name -> slice, path (member last), kind, setter. Written by tools/dev/vm_forwarders.py apply."]
    for k in sorted(table):
        s, m, kind, st = table[k]
        rows.append("\t".join([k, s, ".".join(m), kind, "1" if st else "0"]))
    write(TABLE, "\n".join(rows) + "\n")


def full_table():
    t, kept, members, fwds = build_table()
    p = load_persisted()
    for k, v in p.items():
        t.setdefault(k, v)
    return t, kept, members, fwds


# ---------------------------------------------------------------- the files
def scan_files():
    out = []
    for d in SCAN_DIRS:
        for dp, _, fns in os.walk(os.path.join(ROOT, d)):
            for fn in fns:
                if fn.endswith(".kt"):
                    p = os.path.join(dp, fn)
                    if p != VM:
                        out.append(p)
    return sorted(out)


def receivers_for(text):
    rs = set(RECEIVERS)
    for m in re.finditer(r"\b(\w+)\s*:\s*(?:com\.onpath\.app\.ui\.)?AppViewModel\b", text):
        rs.add(m.group(1))
    for m in re.finditer(r"\b(?:val|var)\s+(\w+)\s*(?::[^=\n]+)?(?:=|by)\s*[^\n]*AppViewModel", text):
        rs.add(m.group(1))
    return rs


def rewrite_text(text, table, vmfile=False, roots=None):
    """returns (new text, count); roots collects the slices the new text reaches"""
    if not any(k in text for k in table):
        return text, 0
    rs = ["this@AppViewModel"] if vmfile else receivers_for(text)
    rs_re = "|".join(sorted((re.escape(r) if r != "this@AppViewModel" else r"this@AppViewModel" for r in rs), key=len, reverse=True))
    names = "|".join(sorted(map(re.escape, table), key=len, reverse=True))
    n = 0

    # one pass: `recv.name`, `recv::name`, `recv.name = ` all get `recv.slice.member`; `recv?.name` too
    def sub(m):
        nonlocal n
        recv, op, name = m.group(1), m.group(2), m.group(3)
        root, path = table[name][0], table[name][1]
        n += 1
        if roots is not None:
            roots.add(root)
        head = ".".join([root] + path[:-1])
        if op == "::":
            return "%s.%s::%s" % (recv, head, path[-1])
        return "%s%s%s.%s" % (recv, op, head, path[-1])

    pat = re.compile(r"(?<![\w.])(" + rs_re + r")(\?\.|\.|::)(" + names + r")\b(?!\s*->)")
    new = pat.sub(sub, text)
    return new, n


def strip_comments_len(text):
    return text


def rewrite_file_lines(t, table, vmfile, roots):
    out_lines, cnt = [], 0
    for l in t.split("\n"):
        st = l.lstrip()
        if st.startswith("*") or st.startswith("//") or st.startswith("/*"):
            out_lines.append(l)
            continue
        code, sep, cm = l.partition(" //") if '"' not in l else (l, "", "")
        nl, c = rewrite_text(code, table, vmfile, roots)
        out_lines.append(nl + sep + cm)
        cnt += c
    return "\n".join(out_lines), cnt


def plan_or_apply(apply, delete):
    table, kept, members, fwds = full_table()
    total = 0
    changed = {}
    roots = set()
    for p in scan_files():
        t, cnt = rewrite_file_lines(read(p), table, False, roots if "/src/main/" in p or "/androidTest/" in p else None)
        if cnt:
            changed[p] = (cnt, t)
            total += cnt
    vmt, vcnt = rewrite_file_lines(read(VM), table, True, None)
    for p, (c, t) in sorted(changed.items()):
        print("%5d  %s" % (c, os.path.relpath(p, ROOT)))
        if apply:
            write(p, t)
    if vcnt:
        print("%5d  %s (this@AppViewModel reads)" % (vcnt, os.path.relpath(VM, ROOT)))
    if not changed and not vcnt:
        print("nothing to do")
    if apply:
        save_persisted(table)
        if vcnt:
            write(VM, vmt)
        publish(roots)
        annotate_types({v[0] for v in table.values()})
    if apply and delete:
        delete_forwarders(table)
    return total


def annotate_types(roots):
    """A slice that is read through its own host objects needs its type written down (else Kotlin: "Type checking has run
    into a recursive problem" once the forwarders are gone): `val x = Type(` / `val x by lazy { Type(` -> `val x: Type ...`."""
    text = read(VM)
    lines = text.split("\n")
    done = []
    for i, l in enumerate(lines):
        m = re.match(r"^(    (?:private |internal )?val (\w+))(\s*=\s*|\s+by lazy \{\s*)(\w[\w.]*)?(.*)$", l)
        if not m or m.group(2) not in roots:
            continue
        name = m.group(2)
        if m.group(3).strip() == "=":
            typ = m.group(4)
            if typ and typ.split(".")[-1][:1].isupper() and m.group(5).startswith("("):
                lines[i] = "%s: %s =%s%s%s" % (m.group(1), typ, m.group(3)[m.group(3).index("=") + 1:], typ, m.group(5))
                done.append(name)
        else:  # by lazy { ... on this line or the next
            nxt = lines[i + 1].strip() if i + 1 < len(lines) else ""
            mt = re.match(r"^([A-Z][\w.]*)\(", nxt)
            if mt and not m.group(4):
                lines[i] = "%s: %s by lazy {" % (m.group(1), mt.group(1))
                done.append(name)
    if done:
        write(VM, "\n".join(lines))
        print("explicit type written: " + ", ".join(done))


def publish(roots):
    """a slice read by a screen or an E2E class is not `private` any more (`val` / `internal val` stay as they are)"""
    lines = read(VM).split("\n")
    done = []
    for i, l in enumerate(lines):
        m = re.match(r"^    private (val (\w+)\b.*)$", l)
        if m and m.group(2) in roots:
            lines[i] = "    " + m.group(1)
            done.append(m.group(2))
    if done:
        write(VM, "\n".join(lines))
        print("now public: " + ", ".join(done))


def delete_forwarders(table):
    lines = read(VM).split("\n")
    members = parse_members(lines)
    fwds = [parse_forwarder(m) for m in members]
    kill = []
    for m, f in zip(members, fwds):
        if f and f.name in table and f.name not in KEEP and sum(1 for x in members if x.name == f.name) == 1:
            s = m.start
            # attached doc comment above (no blank line between), but never a section header "// ---"
            while s > 0 and re.match(r"^    (/\*\*|\*|\*/|//)", lines[s - 1]) and not re.match(r"^    // ---", lines[s - 1]):
                s -= 1
            # an attached doc that starts at /** may only be taken whole
            if s < m.start and not lines[s].lstrip().startswith(("/**", "//")):
                while s < m.start and not lines[s].lstrip().startswith(("/**", "//")):
                    s += 1
            kill.append((s, m.end))
    dead = set()
    for s, e in kill:
        dead.update(range(s, e + 1))
    write(VM, "\n".join(l for i, l in enumerate(lines) if i not in dead))
    print("deleted %d forwarders (%d lines)" % (len(kill), len(dead)))


# ---------------------------------------------------------------- the compiler log
ERR = re.compile(r"^e: (?:file://)?(/\S+\.kt):(\d+):(\d+) (?:Unresolved reference|Cannot find a parameter|.*)'?(\w+)?'?", re.M)


def roots_of(table):
    return sorted({v[0] for v in table.values()})


def fix_from_log(log):
    table, kept, members, fwds = full_table()
    errs = {}
    for m in re.finditer(r"^e: (?:file://)?(/\S+?\.kt):(\d+):(\d+)\s+Unresolved reference[: ]+'?(\w+)'?", read(log), re.M):
        p, ln, col, name = m.group(1), int(m.group(2)), int(m.group(3)), m.group(4)
        if name in table:
            errs.setdefault(p, []).append((ln, col, name))
    for p, lst in sorted(errs.items()):
        lines = read(p).split("\n")
        for ln, col, name in sorted(set(lst), reverse=True):
            l = lines[ln - 1]
            if l[col - 1:col - 1 + len(name)] != name:
                continue
            root, path = table[name][0], table[name][1]
            before = l[:col - 1]
            # already reached through the slice (a follow-up error of an inference cascade): leave it
            if before.endswith(tuple(r + "." for r in roots_of(table))) or before.endswith(("." + ".".join(path[:-1]) + ".") if len(path) > 1 else "\0"):
                continue
            if before.endswith("::"):  # a bound reference: ::x -> slice::x
                lines[ln - 1] = before[:-2] + ".".join([root] + path[:-1]) + "::" + path[-1] + l[col - 1 + len(name):]
                continue
            lines[ln - 1] = before + ".".join([root] + path) + l[col - 1 + len(name):]
        write(p, "\n".join(lines))
        print("%5d  %s" % (len(set(lst)), os.path.relpath(p, ROOT)))
    if not errs:
        print("nothing to do")


# ---------------------------------------------------------------- the inventory
def reader_counts(files_text):
    """{category: Counter(name)} of `recv.name` / `recv::name` reads"""
    from collections import Counter
    out = {}
    for cat, text in files_text.items():
        rs = receivers_for(text)
        pat = re.compile(r"(?<![\w.])(?:" + "|".join(map(re.escape, rs)) + r")(?:\?\.|\.|::)(\w+)")
        out[cat] = Counter(pat.findall(text))
    return out


def inventory(only_dead=False):
    table, kept, members, fwds = full_table()
    cats = {"screens": "app/src/main", "androidTest": "app/src/androidTest", "unit": ["app/src/test", "state/src/test", "core/src/test"]}
    texts = {"screens": "", "androidTest": "", "unit": ""}
    for p in scan_files():
        rel = os.path.relpath(p, ROOT)
        cat = "screens" if rel.startswith("app/src/main") else "androidTest" if rel.startswith("app/src/androidTest") else "unit" if "/src/test/" in rel else None
        if cat:
            texts[cat] += "\n" + read(p)
    vmtext = read(VM)
    rc = reader_counts(texts)
    from collections import Counter
    code_lines = [l for l in vmtext.split("\n") if not l.lstrip().startswith(("*", "//", "/*"))]
    code = "\n".join(re.sub(r"\s//.*$", "", l) if '"' not in l else l for l in code_lines).replace("this@AppViewModel.", "")
    idc = Counter(re.findall(r"(?<![\w.])(\w+)\b", code))
    if not only_dead:
        print("kind\tname\tlines\ttarget\tscreens\tandroidTest\tunit\tinside-vm")
    counts = {}
    dead = []
    for m, f in zip(members, fwds):
        kind = "forwarder" if f and f.name in table else ("forwarder-kept" if f else m.kind)
        tgt = ""
        if f and f.name in table:
            tgt = ".".join([table[f.name][0]] + table[f.name][1])
        elif f:
            tgt = "%s -> %s" % (f.recv or "", f.target)
        r = {c: rc[c][m.name] for c in rc} if m.kind in ("val", "var", "fun") else {}
        own = [l for l in m.lines if not l.lstrip().startswith(("*", "//", "/*"))]
        inside = idc[m.name] - len(re.findall(r"(?<![\w.])" + re.escape(m.name) + r"\b", "\n".join(own).replace("this@AppViewModel.", ""))) if m.kind in ("val", "var", "fun") else 0
        counts[kind] = counts.get(kind, 0) + 1
        if kind == "forwarder" and sum(r.values()) + inside == 0:
            dead.append(m.name)
        if not only_dead:
          print("\t".join(map(str, [kind, m.name, m.end - m.start + 1, tgt, r.get("screens", ""), r.get("androidTest", ""), r.get("unit", ""), inside])))
    if only_dead:
        return dead
    print("# counts: " + ", ".join("%s=%d" % kv for kv in sorted(counts.items())), file=sys.stderr)
    for n, why in kept:
        print("# kept forwarder %s: %s" % (n, why), file=sys.stderr)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    mode = argv[1]
    if mode == "inventory":
        inventory()
    elif mode == "dead":
        print("\n".join(inventory(True)))
    elif mode == "delete-dead":
        dead = inventory(True)
        # names the compiler still finds read (E2E `it.x` / `with(vm)` reads the receiver scan cannot see): --except=a,b
        for a in argv[2:]:
            if a.startswith("--except="):
                dead = [d for d in dead if d not in a.split("=", 1)[1].split(",")]
        t, _, _, _ = full_table()
        delete_forwarders({k: v for k, v in t.items() if k in dead})
    elif mode == "table":
        t, kept, _, _ = full_table()
        for k in sorted(t):
            print(k, "->", ".".join([t[k][0]] + t[k][1]), t[k][2])
        for n, why in kept:
            print("# kept", n, why)
    elif mode == "plan":
        plan_or_apply(False, False)
    elif mode == "apply":
        plan_or_apply(True, "--delete" in argv)
    elif mode == "types":
        annotate_types({v[0] for v in full_table()[0].values()})
    elif mode == "fix":
        fix_from_log(argv[2])
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
