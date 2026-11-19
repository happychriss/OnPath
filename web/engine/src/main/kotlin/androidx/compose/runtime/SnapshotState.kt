package androidx.compose.runtime

import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlin.reflect.KProperty

/*
 * Web stand-in for the Compose runtime names a `:state` file listed in state/web-ready.txt may import
 * (state/src/test/…/WebReadyRuleTest.kt `runtimeNames`): mutableStateOf, getValue, setValue, mutableStateListOf,
 * mutableIntStateOf – and the types those five return, nothing else. The planner has no Compose runtime jar: the slices
 * (RoutesState, SaveDialog, …) run in the page's Wasm, where a `by mutableStateOf(…)` property is a plain field.
 *
 * What this is: a holder. What it does NOT do (the real runtime does):
 *  - no snapshot isolation: a write is seen by every reader at once, there is no transaction, no `Snapshot.withMutableSnapshot`;
 *  - no observers: nothing is told when a value changes (no recomposition, no `snapshotFlow`, no `derivedStateOf` –
 *    neither exists here, a file that uses one does not compile); the page asks for the state after each call
 *    (WebState.kt: `routesStateJson()`, `saveDialogJson()`);
 *  - no mutation policy: `mutableStateOf(value)` takes the value only (the real second parameter, the equality policy,
 *    only decides whether observers are told – there are none);
 *  - no thread safety (the page is single-threaded).
 * The JVM tests of the engine run the same holder (web/engine has no Compose on its classpath either).
 */

/** A value that can be read (the real `State`: reading subscribes; here it only reads). */
interface State<out T> { val value: T }

/** A value that can be read and written (the real `MutableState`: writing notifies; here it only writes). */
interface MutableState<T> : State<T> { override var value: T }

/** The real `IntState`: an Int without boxing. */
interface IntState : State<Int> {
    val intValue: Int
    override val value: Int get() = intValue
}

/** The real `MutableIntState`. */
interface MutableIntState : IntState, MutableState<Int> {
    override var intValue: Int
    override var value: Int
        get() = intValue
        set(v) { intValue = v }
}

private class Holder<T>(override var value: T) : MutableState<T> {
    override fun toString() = "MutableState(value=$value)"
}

private class IntHolder(override var intValue: Int) : MutableIntState {
    override fun toString() = "MutableIntState(value=$intValue)"
}

/** A plain holder of [value] – no snapshot, no observers (see the file's comment). */
fun <T> mutableStateOf(value: T): MutableState<T> = Holder(value)

/** A plain holder of an Int. */
fun mutableIntStateOf(value: Int): MutableIntState = IntHolder(value)

/** A plain list (see [SnapshotStateList]). */
fun <T> mutableStateListOf(): SnapshotStateList<T> = SnapshotStateList()

fun <T> mutableStateListOf(vararg elements: T): SnapshotStateList<T> = SnapshotStateList<T>().also { it.addAll(elements) }

/** `val x by state` */
operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value

/** `var x by state` */
operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) { this.value = value }

/** `val n by mutableIntStateOf(0)` */
operator fun IntState.getValue(thisObj: Any?, property: KProperty<*>): Int = intValue

/** `var n by mutableIntStateOf(0)` */
operator fun MutableIntState.setValue(thisObj: Any?, property: KProperty<*>, value: Int) { intValue = value }
