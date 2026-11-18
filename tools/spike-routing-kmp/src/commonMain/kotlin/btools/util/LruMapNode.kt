package btools.util

abstract class LruMapNode {
    var nextInBin: LruMapNode? = null // next entry for hash-bin
    var next: LruMapNode? = null // next in lru sequence (towards mru)
    var previous: LruMapNode? = null // previous in lru sequence (towards lru)

    var hash: Int = 0
}
