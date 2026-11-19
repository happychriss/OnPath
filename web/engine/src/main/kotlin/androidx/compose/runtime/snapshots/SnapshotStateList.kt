package androidx.compose.runtime.snapshots

/**
 * Web stand-in: what `mutableStateListOf` returns (androidx/compose/runtime/SnapshotState.kt) – an ArrayList under the
 * real class's name, so an inferred type reads the same in app and planner. No snapshot isolation, no observers: a
 * loop over it sees a change made meanwhile (the real list iterates over the snapshot it started with; here that is a
 * ConcurrentModificationException, as with any ArrayList).
 * The rule test does not allow a listed file to IMPORT this name yet (WebReadyRuleTest, 2026-11-19: `PlanDocument` is
 * "one step away" because of it) – the class is here because `mutableStateListOf` needs a return type.
 */
class SnapshotStateList<T> private constructor(private val list: ArrayList<T>) : MutableList<T> by list, RandomAccess {
    constructor() : this(ArrayList())

    override fun equals(other: Any?): Boolean = list == other
    override fun hashCode(): Int = list.hashCode()
    override fun toString(): String = list.toString()
}
