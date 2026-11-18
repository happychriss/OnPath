# Porting rules: BRouter (Java) → Kotlin commonMain

Source: `abrensch/brouter` commit `29898106` (= the app's `app/libs/brouter-*-1.7.11-beta-onpath1.jar`, checked class by
class). Target: `src/commonMain/kotlin/btools/<package>/` – **pure Kotlin, no `java.*`, no `kotlin.jvm.*`** (it must
compile for `linuxX64`). The goal is the **same result bit for bit**, not nicer code.

## Rules
1. **One Java file → one Kotlin file**, same package, same class / method / field names, same order of statements.
   No refactoring, no "idiomatic" rewrite, no reordering of floating point arithmetic (`a*b+c` stays `a*b+c`; no `fma`,
   no `pow` for `x*x`). Comments may be shortened; keep the ones that explain a trick.
2. Fields stay mutable `var` with the Java default (`0`, `null`, `false`). Java package-private / protected → plain
   Kotlin `public` (or `internal`); getters stay functions (`getILon()` stays `fun getILon()`), no property conversion.
   `static` → `companion object` (constants `const val`), a class with only statics → `object`.
3. Numbers – the classic traps, check each:
   - `>>>` → `ushr`, `>>` → `shr`, `<<` → `shl`, `&|^~` → `and or xor inv()`. **Kotlin infix operators have lower
     precedence than `+ - *`** and equal precedence among themselves (left to right) – parenthesise every one exactly as
     Java would evaluate it (Java: shifts bind tighter than `&`, `&` tighter than `^`, `^` tighter than `|`, all weaker
     than `+`).
   - Java promotes `byte` / `short` / `char` to `int` in arithmetic: write `.toInt()` explicitly; `b & 0xff` →
     `(b.toInt() and 0xff)`. `(byte) x` → `x.toByte()`, `(short) x` → `x.toShort()`, `(int) aLong` → `.toInt()`,
     `(int) aDouble` → `.toInt()` (same saturation), `(long) aDouble` → `.toLong()`, `(char)` → `.toChar()` (from Int).
   - int × int that Java widens to long only **after** the multiplication must still overflow in Int.
     `long x = a * b` (ints) → `(a * b).toLong()`; `(long) a * b` → `a.toLong() * b`.
   - Integer division and `%` are the same. `float` vs `double`: keep the declared type; a Java `float * double` is
     double arithmetic – convert the float with `.toDouble()`, and a result stored in a `float` gets `.toFloat()` at
     the same place Java narrows.
   - Compound assignment narrows silently in Java (`aByte += x`, `anInt += aDouble`, `aFloat += aDouble`): write the cast.
   - `Math.xxx` → `kotlin.math.xxx` (same functions); `Math.round(double)` → `kotlin.math.floor(x + 0.5).toLong()`
     (Java semantics, NOT `roundToLong`, which rounds half to even), `Math.round(float)` → `floor(x + 0.5f).toInt()`.
   - `Integer.MAX_VALUE` → `Int.MAX_VALUE`, `Float.MAX_VALUE` → `Float.MAX_VALUE`; `Double.MIN_VALUE` → `Double.MIN_VALUE`.
4. Strings: no `String.format` (use `btools.platform.formatFixed(value, decimals)` for messages; never for values
   that steer routing), `StringTokenizer` → `btools.platform.tokenize(s, delims)`, `Integer.parseInt` → `.toInt()`,
   `Float.parseFloat` → `.toFloat()` (NumberFormatException exists in Kotlin common). `s.split(regex)` – Java's `split`
   drops trailing empty strings, Kotlin's does not: keep Java's behaviour where the code depends on it.
   `equals` → `==`; reference comparison `==` on objects → `===`.
5. Files: `java.io.File` → `btools.platform.File` (path wrapper), `RandomAccessFile` → `btools.platform.RandomAccessFile`
   (`seek`, `readFully(buf, off, len)`, `length`, `close`), a text file → `btools.platform.readFileLines(path)`,
   bytes → `readFileBytes(path)`, `IOException` → `btools.platform.IOException`, `System.currentTimeMillis()` →
   `btools.platform.currentTimeMillis()`. Need one more platform call? Do **not** add expect / actual yourself – write the
   call you need, list it in your report under "platform", the lead adds it.
6. Collections: `ArrayList` / `HashMap` / `List` / `Map` → Kotlin's (`ArrayList`, `HashMap`, `MutableList`, `MutableMap`).
   **Iteration order of a HashMap / HashSet differs between the JVM and Kotlin/Native.** Wherever Java iterates a
   `HashMap` / `HashSet` (keySet, values, entrySet), mark the line `// PORT-NOTE hash order` and list it in your report.
   `TreeMap` / `TreeSet` do not exist: sorted iteration → collect and sort explicitly; pure statistics / debug dumps may
   be dropped (say so). `Collections.sort(list, cmp)` is a stable sort → `list.sortWith(cmp)` (stable too).
   `list.remove(int)` → `removeAt(i)`; careful with `remove(Object)` on a list of Int.
7. Threads: none. `synchronized` blocks → plain blocks. `RoutingEngine extends Thread` → a plain class; `doRun`
   runs on the caller. `terminate()` just sets the flag.
8. `System.out` / `System.err` debug prints → `println` / `btools.platform.printErr`, or drop when purely debug.
   `Runtime` memory checks / `OutOfMemoryError` handling: keep the control flow, see "Out of scope" – mark `// PORT-NOTE memory`.
   `RuntimeException` / `IllegalArgumentException` → Kotlin's of the same name.
9. `instanceof` → `is`, casts → `as`. Anonymous classes → `object :`. Inner (non-static) classes → `inner class`.
   `switch` with fall-through: reproduce the fall-through exactly (it is used on purpose in places).
   `for (int i = 0; i < n; i++)` where the body changes `i` or `n` → a `while` loop, not `for (i in 0 until n)`.
   Labeled `break` / `continue` → Kotlin labels. Assignments inside expressions (`while ((x = f()) != null)`,
   `a = b = c`, `x[i++] = y`) → split, keeping the evaluation order.
10. Nullability: mechanical – a field that Java sets to null is `T?`; use `!!` where Java would have thrown an NPE.
    Do not add null-tolerant behaviour.
11. Something does not port cleanly or you are unsure about the semantics → keep going, mark `// PORT-NOTE <what>`
    and list it in the report. Never guess silently.

## Out of scope (do not port; where a kept class refers to them, cut the branch and mark `// PORT-CUT <what>`)
- util: `StackSampler`, `MixCoderDataInputStream`, `MixCoderDataOutputStream`, `DiffCoderDataInputStream`,
  `DiffCoderDataOutputStream`, `DenseLongMap`, `LazyArrayOfLists` (map creation only – keep one if a kept class needs it).
- codec: everything stays, but the **encode** side used only when rd5 files are written may be cut (`PORT-CUT encode`).
- expressions: `IntegrityCheckProfile`, `ProfileComparator`, `BExpressionContext.generateRandomValues` and the lookup dumps.
- mapaccess: `Rd5DiffManager`, `Rd5DiffTool`, `Rd5DiffValidator`, `StorageConfigHelper`.
- core: `Format*`, `Formatter`, `RoutingParamCollector`, `Kinematic*` (car profiles; `OsmPath.computeKinematic` /
  the `StdPath` implementation stay), `AreaReader` (`AreaInfo` stays, `rc.ai` is always null), `RoutingHelper`,
  the engine modes other than `BROUTER_ENGINEMODE_ROUTING` (seed / get-elevation / get-info / round trip),
  writing output files, the debug log (`debug.txt`) and the stack sampler, `OsmTrack.writeBinary` / `readBinary` and the
  stream methods of `OsmPathElement` (raw tracks: `routingContext.rawTrackPath` is always null).
  `RoutingEngine.getTime()` may return the seconds as a plain string.

## Layout
`src/commonMain/kotlin/btools/{util,codec,expressions,mapaccess,router}/X.kt`. Platform edge: `btools/platform/Platform.kt`
(read it first). The lead writes `spike/Main.kt` and the tests – helpers only add files in their own packages.
