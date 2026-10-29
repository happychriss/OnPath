package com.onpath.app.cells

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny stand-in for the doorman (tools/cloud/worker/src/api.js): /v1/cells/request, /status, /manifest and
 * /v1/files/… with Range. Cells become ready after [buildPolls] status calls. Serves files from [root] (cells/f2/…).
 */
class FakeDoorman(val root: File, val key: String = "test-key", var buildPolls: Int = 1) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url: String get() = "http://127.0.0.1:${socket.localPort}/v1"

    /** "10-543-361" → version served when ready. */
    val versions = ConcurrentHashMap<String, String>()
    val failing = ConcurrentHashMap<String, String>()
    /** Cells whose served (ready) version is being rebuilt: answered "ready" + `updating: true`, like the doorman. */
    val updating: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val polls = ConcurrentHashMap<String, Int>()
    val log = CopyOnWriteArrayList<String>()
    /** File downloads cut after this many bytes (simulates a broken connection), null = whole file. */
    @Volatile var cutAfter: Long? = null
    /** Corrupt one byte in the next file response. */
    @Volatile var corruptNext = false
    /** Like a doorman before region packages: any request / status naming a z7 id is a 400 "bad cell id". */
    @Volatile var rejectPackages = false
    /** Ids named in request calls, one list per call (to see that packages go in calls of their own). */
    val requestCalls = CopyOnWriteArrayList<List<String>>()

    /** Minimal HTTP/1.1 exchange (one request per connection, Connection: close) – the Android test classpath has no JDK http server. */
    private class Exchange(val s: Socket) {
        val method: String; val path: String; val query: String?
        val headers = HashMap<String, String>()
        val body: String
        private val out = s.getOutputStream()
        init {
            val inp = java.io.BufferedInputStream(s.getInputStream())
            fun line(): String { val b = java.io.ByteArrayOutputStream(); while (true) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.write(c) }; return b.toString("UTF-8") }
            val req = line().split(" ")
            method = req[0]; path = req[1].substringBefore('?'); query = req[1].substringAfter('?', "").ifEmpty { null }
            while (true) { val l = line(); if (l.isEmpty()) break; headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim() }
            val n = headers["content-length"]?.toInt() ?: 0
            body = String(ByteArray(n).also { var o = 0; while (o < n) { val r = inp.read(it, o, n - o); if (r < 0) break; o += r } })
        }
        fun header(name: String) = headers[name.lowercase()]
        fun respond(code: Int, body: ByteArray, extra: Map<String, String> = emptyMap(), sendBytes: Int = body.size) {
            val h = StringBuilder("HTTP/1.1 $code X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
            extra.forEach { (k, v) -> h.append("$k: $v\r\n") }
            h.append("\r\n")
            out.write(h.toString().toByteArray()); out.write(body, 0, sendBytes); out.flush()
        }
    }

    init {
        Thread {
            while (!socket.isClosed) {
                val c = try { socket.accept() } catch (e: java.io.IOException) { break } // expected: closed at the end of a test
                Thread { c.use { s -> try { handle(Exchange(s)) } catch (e: Exception) { log += "error: $e" } } }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    override fun close() = socket.close()

    private fun send(ex: Exchange, code: Int, body: String, headers: Map<String, String> = emptyMap()) =
        ex.respond(code, body.toByteArray(), headers + ("Content-Type" to "application/json"))

    private fun stateOf(id: String): JSONObject {
        failing[id]?.let { return JSONObject().put("id", id).put("state", "failed").put("reason", it) }
        val v = versions[id] ?: return JSONObject().put("id", id).put("state", "none")
        val n = polls.getOrDefault(id, 0)
        return if (n >= buildPolls) JSONObject().put("id", id).put("state", "ready").put("version", v).put("osm_date", v).put("size", 1000)
            .also { if (id in updating) it.put("updating", true) }
        else JSONObject().put("id", id).put("state", "building").put("step", "base").put("progress", 0.5).put("eta_s", 60)
    }

    private fun handle(ex: Exchange) {
        val path = ex.path
        log += "${ex.method} ${path}${ex.query?.let { "?$it" } ?: ""}" + (ex.header("Range")?.let { " Range:$it" } ?: "")
        if (ex.header("X-OnPath-Key") != key) return send(ex, 401, """{"error":"unknown app key"}""")
        val named = if (path == "/v1/cells/request") JSONObject(ex.body).getJSONArray("cells").let { a -> (0 until a.length()).map { a.getString(it) } }
            else if (path == "/v1/cells/status") ex.query!!.substringAfter("ids=").substringBefore("&").split(",") else emptyList()
        if (rejectPackages && named.any { it.startsWith("7-") }) return send(ex, 400, """{"error":"bad cell id"}""")
        when {
            path == "/v1/cells/request" -> {
                val ids = named
                requestCalls += ids
                send(ex, 200, JSONObject().put("cells", JSONArray(ids.map { stateOf(it) })).put("poll_s", 15).toString())
            }
            path == "/v1/cells/status" -> {
                val ids = ex.query!!.substringAfter("ids=").substringBefore("&").split(",")
                ids.forEach { polls.merge(it, 1, Int::plus) }
                send(ex, 200, JSONObject().put("cells", JSONArray(ids.map { stateOf(it) })).put("poll_s", 15).toString())
            }
            path.endsWith("/manifest") -> {
                val (z, x, y) = path.removePrefix("/v1/cells/").removeSuffix("/manifest").split("/")
                val v = versions["$z-$x-$y"]
                val f = File(root, "cells/f2/$z/$x/$y/$v/manifest.json")
                if (v == null || !f.isFile) send(ex, 404, """{"error":"not ready"}""") else send(ex, 200, f.readText())
            }
            path.startsWith("/v1/files/") -> {
                val f = File(root, path.removePrefix("/v1/files/"))
                if (!f.isFile) return send(ex, 404, """{"error":"not found"}""")
                var bytes = f.readBytes()
                if (corruptNext) { corruptNext = false; bytes = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() } }
                val range = ex.header("Range")
                var start = 0
                var code = 200
                val extra = HashMap<String, String>()
                if (range != null) {
                    start = range.removePrefix("bytes=").substringBefore("-").toInt()
                    if (start >= bytes.size) return send(ex, 416, "", mapOf("Content-Range" to "bytes */${bytes.size}"))
                    code = 206
                    extra["Content-Range"] = "bytes $start-${bytes.size - 1}/${bytes.size}"
                }
                val body = bytes.copyOfRange(start, bytes.size)
                val cut = cutAfter
                ex.respond(code, body, extra, if (cut != null && cut < body.size) cut.toInt() else body.size)
            }
            else -> send(ex, 404, """{"error":"not found"}""")
        }
    }

    /** Publishes a synthetic region package version (z7, map-cells.md §20) under cells/f2/7/x/y/version/. */
    fun publishPackage(p: CellId, version: String, tag: String = "$p@$version"): CellManifest {
        val m = CellFixtures.pkg(File(root, "cells/f2/${p.path}/$version"), p, version, tag)
        versions[p.id] = version
        polls.remove(p.id)
        return m
    }

    /** Publishes a synthetic cell version under cells/f2/z/x/y/version/ (as the builder would). */
    fun publish(cell: CellId, version: String, tag: String = "$cell@$version"): CellManifest {
        val m = CellFixtures.cell(File(root, "cells/f2/${cell.path}/$version"), cell, version, tag)
        versions[cell.id] = version
        polls.remove(cell.id)
        return m
    }
}
