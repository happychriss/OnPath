package com.onpath.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import kotlin.random.Random

/**
 * The web build's BRouter OsmNodesMap (src/main/java/btools/mapaccess) cleans up peninsulas without recursion: in TeaVM's
 * Wasm a deep recursion is an uncatchable RangeError that aborted every plan near München (2026-11-19: München →
 * Holzkirchen, "Maximum call stack size exceeded" in minVisitIdInSubtree). The tests build node graphs through
 * reflection, so the same code drives the web class and BRouter's original from the jar (in a class loader of its own).
 */
class OsmNodesMapTest {
    private val webLoader = OsmNodesMapTest::class.java.classLoader
    private val jarLoader = URLClassLoader(
        File("../../app/libs").listFiles { f -> f.name.startsWith("brouter-") && f.name.endsWith(".jar") }!!
            .map { it.toURI().toURL() }.toTypedArray(),
        ClassLoader.getPlatformClassLoader())

    /** A graph in one class loader: nodes, the map, and what the cleanup left (visit ids + neighbours). */
    private class Graph(loader: ClassLoader, n: Int) {
        val nodeC = loader.loadClass("btools.mapaccess.OsmNode")
        val linkC = loader.loadClass("btools.mapaccess.OsmLink")
        val mapC = loader.loadClass("btools.mapaccess.OsmNodesMap")
        val nodes = Array<Any>(n) { nodeC.getConstructor(Int::class.java, Int::class.java).newInstance(it, 0) }
        val map: Any = mapC.getConstructor().newInstance()
        private val visitID = nodeC.getField("visitID")
        private val firstlink = nodeC.getField("firstlink")
        private val getNext = linkC.getMethod("getNext", nodeC)
        private val getTarget = linkC.getMethod("getTarget", nodeC)

        fun visit(i: Int, id: Int) = visitID.setInt(nodes[i], id)
        fun hollow(i: Int) { nodeC.getMethod("setHollow").invoke(nodes[i]) }
        fun link(a: Int, b: Int) {
            val l = linkC.getConstructor(nodeC, nodeC).newInstance(nodes[a], nodes[b])
            nodeC.getMethod("addLink", linkC, Boolean::class.java, nodeC).invoke(nodes[a], l, false, nodes[b])
        }
        fun cleanup(mode: Int) {
            mapC.getField("cleanupMode").setInt(map, mode)
            val arr = java.lang.reflect.Array.newInstance(nodeC, nodes.size)
            nodes.forEachIndexed { i, x -> java.lang.reflect.Array.set(arr, i, x) }
            mapC.getMethod("cleanupAndCount", arr.javaClass).invoke(map, arr)
        }
        fun neighbours(i: Int): List<Int> {
            val out = ArrayList<Int>()
            var l = firstlink.get(nodes[i])
            while (l != null) {
                out += nodes.indexOfFirst { it === getTarget.invoke(l, nodes[i]) }
                l = getNext.invoke(l, nodes[i])
            }
            return out
        }
        fun state(): String = nodes.indices.joinToString("\n") { "$it id=${visitID.getInt(nodes[it])} -> ${neighbours(it)}" } +
            "\nnodesCreated=${mapC.getField("nodesCreated").getInt(map)} lastVisitID=${mapC.getField("lastVisitID").getInt(map)}"
    }

    @Test
    fun aLongDeadEndIsCutOffInASmallStack() {
        // border node 0 – 1 – 2 – … – n-1 (dead end): the recursive original needs a frame per node; a 256 KB stack is
        // far too small for 200 000 of them (BRouter's catch on the JVM would leave the chain linked)
        val n = 200_000
        val g = Graph(webLoader, n)
        g.visit(0, 1)
        for (i in 0 until n - 1) g.link(i, i + 1)
        var error: Throwable? = null
        val t = Thread(null, { try { g.cleanup(1) } catch (e: Throwable) { error = e } }, "small-stack", 256 * 1024L)
        t.start(); t.join()
        assertNull(error)
        assertEquals(emptyList<Int>(), g.neighbours(0))
        assertEquals(emptyList<Int>(), g.neighbours(n / 2))
        assertEquals(emptyList<Int>(), g.neighbours(n - 1))
    }

    @Test
    fun sameResultAsBRouterOnRandomGraphs() {
        assertTrue("the test must compare two different classes",
            Graph(webLoader, 1).mapC !== Graph(jarLoader, 1).mapC)
        for (seed in 0 until 300) for (mode in 1..2) {
            val r = Random(seed)
            val n = 5 + r.nextInt(60)
            val visits = IntArray(n) { listOf(0, 0, 0, 1, 1, 500)[r.nextInt(6)] }
            val hollows = (0 until n).filter { r.nextInt(10) == 0 }
            val edges = List(n + r.nextInt(n * 2)) { r.nextInt(n) to r.nextInt(n) }.filter { it.first != it.second }
            val states = listOf(webLoader, jarLoader).map { loader ->
                val g = Graph(loader, n)
                visits.forEachIndexed { i, v -> g.visit(i, v) }
                hollows.forEach { g.hollow(it) }
                edges.forEach { (a, b) -> g.link(a, b) }
                g.cleanup(mode)
                g.state()
            }
            assertEquals("seed $seed mode $mode", states[1], states[0])
        }
    }
}
