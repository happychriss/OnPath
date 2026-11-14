"""Tour-suggestions POC – a tiny pure-Python reader for PMTiles v3 + Mapbox vector tiles (no dependency).

Enough to read the app's map areas (base.pmtiles / overlays.pmtiles): tile lookup, gzip, layers → features with
tags and geometry in lon / lat. Not fast, not complete (no leaf-directory cache eviction, no polygon winding logic) –
named.py and land.py read a map area once and cache what they need.
"""
import gzip
import math
import struct


def _varint(b, i):
    r = 0
    s = 0
    while True:
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        if c < 0x80:
            return r, i
        s += 7


def _zz(n):
    return (n >> 1) ^ -(n & 1)


def tile_id(z, x, y):
    acc = sum(4 ** i for i in range(z))
    n = 1 << z
    d = 0
    s = n >> 1
    while s > 0:
        rx = 1 if (x & s) else 0
        ry = 1 if (y & s) else 0
        d += s * s * ((3 * rx) ^ ry)
        if ry == 0:
            if rx == 1:
                x = s - 1 - x
                y = s - 1 - y
            x, y = y, x
        s >>= 1
    return acc + d


class PMTiles:
    def __init__(self, path):
        self.f = open(path, "rb")
        h = self.f.read(127)
        if h[:7] != b"PMTiles" or h[7] != 3:
            raise ValueError("not PMTiles v3: " + path)
        (self.root_off, self.root_len, self.meta_off, self.meta_len, self.leaf_off, self.leaf_len,
         self.data_off, self.data_len) = struct.unpack("<8Q", h[8:72])
        self.internal_comp = h[97]
        self.tile_comp = h[98]
        self.minz, self.maxz = h[100], h[101]
        self._dirs = {}

    def _un(self, b, comp):
        return gzip.decompress(b) if comp == 2 else b

    def _dir(self, off, ln):
        k = (off, ln)
        if k not in self._dirs:
            self.f.seek(off)
            b = self._un(self.f.read(ln), self.internal_comp)
            n, i = _varint(b, 0)
            ids, runs, lens, offs = [], [], [], []
            last = 0
            for _ in range(n):
                v, i = _varint(b, i)
                last += v
                ids.append(last)
            for _ in range(n):
                v, i = _varint(b, i)
                runs.append(v)
            for _ in range(n):
                v, i = _varint(b, i)
                lens.append(v)
            for j in range(n):
                v, i = _varint(b, i)
                offs.append(offs[j - 1] + lens[j - 1] if v == 0 and j > 0 else v - 1)
            self._dirs[k] = (ids, runs, lens, offs)
        return self._dirs[k]

    def tile(self, z, x, y):
        """Raw (decompressed) tile bytes or None."""
        import bisect
        tid = tile_id(z, x, y)
        off, ln = self.root_off, self.root_len
        for _ in range(4):
            ids, runs, lens, offs = self._dir(off, ln)
            j = bisect.bisect_right(ids, tid) - 1
            if j < 0:
                return None
            if runs[j] == 0:
                off, ln = self.leaf_off + offs[j], lens[j]
                continue
            if tid - ids[j] >= runs[j]:
                return None
            self.f.seek(self.data_off + offs[j])
            return self._un(self.f.read(lens[j]), self.tile_comp)
        return None


def _fields(b, i=0, end=None):
    end = len(b) if end is None else end
    while i < end:
        key, i = _varint(b, i)
        fn, wt = key >> 3, key & 7
        if wt == 0:
            v, i = _varint(b, i)
        elif wt == 2:
            ln, i = _varint(b, i)
            v = (i, i + ln)
            i += ln
        elif wt == 5:
            v = b[i:i + 4]
            i += 4
        elif wt == 1:
            v = b[i:i + 8]
            i += 8
        else:
            raise ValueError("wire type %d" % wt)
        yield fn, wt, v


def _value(b, s, e):
    for fn, wt, v in _fields(b, s, e):
        if fn == 1:
            return b[v[0]:v[1]].decode("utf-8", "replace")
        if fn == 2:
            return struct.unpack("<f", v)[0]
        if fn == 3:
            return struct.unpack("<d", v)[0]
        if fn in (4, 5):
            return v
        if fn == 6:
            return _zz(v)
        if fn == 7:
            return bool(v)
    return None


def layers(raw, want, z, x, y):
    """{layer: [ {tags: {...}, type: 1 point | 2 line | 3 polygon, parts: [ [[lon, lat], ...], ... ]} ]} for the
    layers named in `want`."""
    out = {}
    n = float(1 << z)
    for fn, wt, v in _fields(raw):
        if fn != 3:
            continue
        s, e = v
        name, keys, vals, feats, extent = None, [], [], [], 4096
        for f2, w2, v2 in _fields(raw, s, e):
            if f2 == 1:
                name = raw[v2[0]:v2[1]].decode()
                if name not in want:
                    break
            elif f2 == 2:
                feats.append(v2)
            elif f2 == 3:
                keys.append(raw[v2[0]:v2[1]].decode())
            elif f2 == 4:
                vals.append(v2)
            elif f2 == 5:
                extent = v2
        if name not in want:
            continue
        vcache = {}
        res = out.setdefault(name, [])
        for fs, fe in feats:
            tags, gtype, geom = {}, 0, None
            for f3, w3, v3 in _fields(raw, fs, fe):
                if f3 == 2:
                    i = v3[0]
                    while i < v3[1]:
                        k, i = _varint(raw, i)
                        vi, i = _varint(raw, i)
                        if vi not in vcache:
                            vcache[vi] = _value(raw, *vals[vi])
                        tags[keys[k]] = vcache[vi]
                elif f3 == 3:
                    gtype = v3
                elif f3 == 4:
                    geom = v3
            parts = []
            if geom:
                i, end = geom
                cx = cy = 0
                cur = None
                while i < end:
                    c, i = _varint(raw, i)
                    cmd, cnt = c & 7, c >> 3
                    if cmd == 1 or cmd == 2:
                        for _ in range(cnt):
                            dx, i = _varint(raw, i)
                            dy, i = _varint(raw, i)
                            cx += _zz(dx)
                            cy += _zz(dy)
                            if cmd == 1:
                                cur = []
                                parts.append(cur)
                            lon = (x + cx / extent) / n * 360.0 - 180.0
                            lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + cy / extent) / n))))
                            cur.append([lon, lat])
                    elif cmd == 7 and cur:
                        cur.append(list(cur[0]))
            res.append({"tags": tags, "type": gtype, "parts": parts})
    return out


def tiles_of_cell(cx, cy, z):
    """All tiles of zoom z inside the z10 cell (cx, cy)."""
    k = 1 << (z - 10)
    return [(z, cx * k + i, cy * k + j) for i in range(k) for j in range(k)]
