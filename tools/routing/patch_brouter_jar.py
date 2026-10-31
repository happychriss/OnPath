#!/usr/bin/env python3
"""OnPath's one change to BRouter (MIT, https://github.com/abrensch/brouter): in brouter-core's
btools/router/RoutingEngine.class the field `islandNodePairs` loses its `private` flag (becomes package-private).

Why: OnPath's island retry (BRouterEngine.search – a point on a way without a connection to the network is snapped to
the next way) reads the field's freeze count and puts the isolated way into it before matching. With the field private
that needed reflection, which the web planner (TeaVM) does not have; package-private lets btools.router.OnPathHints
(the app's helper in BRouter's package) reach it directly, in the app and the browser alike.

Only the field's access_flags change (0x0002 -> 0x0000); every byte code, every other class and every other jar entry
stay as they were, so routing on the JVM / Android is unchanged. Rewrites the jar in place, entries in the same order
with the same timestamps (reproducible). Refuses anything unexpected (field missing, other flags, already patched with
--check only). Called by tools/routing/build_brouter_jars.sh after the core jar is built.

Usage: patch_brouter_jar.py <brouter-core-….jar>          patch (exit 0; already patched = no change, exit 0)
       patch_brouter_jar.py --check <brouter-core-….jar>  exit 0 only when the jar is patched
"""
import struct
import sys
import zipfile

CLASS = "btools/router/RoutingEngine.class"
FIELD = "islandNodePairs"
DESC = "Lbtools/mapaccess/OsmNodePairSet;"
ACC_PRIVATE = 0x0002


def field_flags_offset(data: bytes) -> tuple[int, int]:
    """(offset of the access_flags u2 of FIELD, its current value) in a class file."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    pos = 8
    (count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    utf8 = {}
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            (n,) = struct.unpack_from(">H", data, pos)
            utf8[i] = data[pos + 2:pos + 2 + n].decode("utf-8", "surrogatepass")
            pos += 2 + n
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):  # Integer, Float, *ref, NameAndType, (Invoke)Dynamic
            pos += 4
        elif tag in (5, 6):  # Long, Double: two slots
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            pos += 2
        elif tag == 15:  # MethodHandle
            pos += 3
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        i += 1
    pos += 6  # access_flags, this_class, super_class
    (ifaces,) = struct.unpack_from(">H", data, pos)
    pos += 2 + 2 * ifaces
    (fields,) = struct.unpack_from(">H", data, pos)
    pos += 2
    found = None
    for _ in range(fields):
        flags, name, desc, attrs = struct.unpack_from(">HHHH", data, pos)
        if utf8.get(name) == FIELD and utf8.get(desc) == DESC:
            if found is not None:
                raise ValueError(f"{FIELD} twice")
            found = (pos, flags)
        pos += 8
        for _ in range(attrs):
            (alen,) = struct.unpack_from(">I", data, pos + 2)
            pos += 6 + alen
    if found is None:
        raise ValueError(f"field {FIELD} {DESC} not found – another BRouter version? Check the patch by hand")
    return found


def patched_class(data: bytes) -> bytes | None:
    """The class with the field package-private, or None when it already is."""
    off, flags = field_flags_offset(data)
    if flags == 0:
        return None
    if flags != ACC_PRIVATE:
        raise ValueError(f"{FIELD} has flags 0x{flags:04x}, expected private (0x0002)")
    return data[:off] + b"\x00\x00" + data[off + 2:]


def main(argv: list[str]) -> int:
    check = argv[:1] == ["--check"]
    args = argv[1:] if check else argv
    if len(args) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    jar = args[0]
    with zipfile.ZipFile(jar) as z:
        infos = z.infolist()
        blobs = {i.filename: z.read(i) for i in infos}
    if CLASS not in blobs:
        raise SystemExit(f"{jar}: no {CLASS} – not the brouter-core jar")
    new = patched_class(blobs[CLASS])
    if check:
        ok = new is None
        print(f"{jar}: {FIELD} {'package-private (patched)' if ok else 'private (NOT patched)'}")
        return 0 if ok else 1
    if new is None:
        print(f"{jar}: already patched")
        return 0
    blobs[CLASS] = new
    tmp = jar + ".tmp"
    with zipfile.ZipFile(tmp, "w") as out:
        for i in infos:
            out.writestr(i, blobs[i.filename], compress_type=i.compress_type)
    import os
    os.replace(tmp, jar)
    print(f"{jar}: {FIELD} private -> package-private")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
