/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.router

import btools.expressions.BExpressionContextNode
import btools.expressions.BExpressionContextWay
import btools.expressions.BExpressionMetaData
import btools.platform.File
import btools.platform.currentTimeMillis

// PORT-NOTE single-threaded: no synchronized; System.getProperty("profileBaseDir") is always null, "debugProfileCache" always false
class ProfileCache {

  private var expctxWay: BExpressionContextWay? = null
  private var expctxNode: BExpressionContextNode? = null
  private var lastProfileFile: File? = null
  private var lastProfileTimestamp: Long = 0
  private var profilesBusy = false
  private var lastUseTime: Long = 0

  companion object {
    private var lastLookupFile: File? = null
    private var lastLookupTimestamp: Long = 0

    private var apc: Array<ProfileCache?> = arrayOfNulls(1)
    private val debug = false

    private fun sameFile(a: File?, b: File?): Boolean {
      return a != null && b != null && a.path == b.path
    }

    fun setSize(size: Int) {
      apc = arrayOfNulls(size)
    }

    fun parseProfile(rc: RoutingContext): Boolean {
      val profileDir: File?
      val profileFile: File
      // PORT-CUT profileBaseDir system property (always null)
      profileDir = File(rc.localFunction!!).getParentFile()
      profileFile = File(rc.localFunction!!)

      rc.profileTimestamp = (profileFile.lastModified() + rc.getKeyValueChecksum()) shl 24
      val lookupFile = if (profileDir == null) File("lookups.dat") else File(profileDir, "lookups.dat") // Java: new File((File) null, name) = name

      // invalidate cache at lookup-table update
      if (!(sameFile(lookupFile, lastLookupFile) && lookupFile.lastModified() == lastLookupTimestamp)) {
        if (lastLookupFile != null) {
          println("******** invalidating profile-cache after lookup-file update ******** ")
        }
        apc = arrayOfNulls(apc.size)
        lastLookupFile = lookupFile
        lastLookupTimestamp = lookupFile.lastModified()
      }

      var lru: ProfileCache? = null
      var unusedSlot = -1

      // check for re-use
      for (i in 0 until apc.size) {
        val pc = apc[i]

        if (pc != null) {
          if ((!pc.profilesBusy) && sameFile(profileFile, pc.lastProfileFile)) {
            if (rc.profileTimestamp == pc.lastProfileTimestamp) {
              rc.expctxWay = pc.expctxWay
              rc.expctxNode = pc.expctxNode
              rc.readGlobalConfig()
              pc.profilesBusy = true
              return true
            }
            lru = pc // name-match but timestamp-mismatch -> we overide this one
            unusedSlot = -1
            break
          }
          if (lru == null || lru.lastUseTime > pc.lastUseTime) {
            lru = pc
          }
        } else if (unusedSlot < 0) {
          unusedSlot = i
        }
      }

      val meta = BExpressionMetaData()

      rc.expctxWay = BExpressionContextWay(rc.memoryclass * 512, meta)
      rc.expctxNode = BExpressionContextNode(0, meta)
      rc.expctxNode!!.setForeignContext(rc.expctxWay)

      meta.readMetaData(lookupFile)

      rc.expctxWay!!.parseFile(profileFile, "global", rc.keyValues)
      rc.expctxNode!!.parseFile(profileFile, "global", rc.keyValues)

      rc.readGlobalConfig()

      if (rc.processUnusedTags) {
        rc.expctxWay!!.setAllTagsUsed()
      }

      if (lru == null || unusedSlot >= 0) {
        lru = ProfileCache()
        if (unusedSlot >= 0) {
          apc[unusedSlot] = lru
          if (debug)
            println("******* adding new profile at idx=" + unusedSlot + " for " + profileFile)
        }
      }

      if (lru!!.lastProfileFile != null) {
        if (debug)
          println("******* replacing profile of age " + ((currentTimeMillis() - lru!!.lastUseTime) / 1000L) + " sec " + lru!!.lastProfileFile + "->" + profileFile)
      }

      lru!!.lastProfileTimestamp = rc.profileTimestamp
      lru!!.lastProfileFile = profileFile
      lru!!.expctxWay = rc.expctxWay
      lru!!.expctxNode = rc.expctxNode
      lru!!.profilesBusy = true
      lru!!.lastUseTime = currentTimeMillis()
      return false
    }

    fun releaseProfile(rc: RoutingContext) {
      for (i in 0 until apc.size) {
        val pc = apc[i]

        if (pc != null) {
          // only the thread that holds the cached instance can release it
          if (rc.expctxWay === pc.expctxWay && rc.expctxNode === pc.expctxNode) {
            pc.profilesBusy = false
            break
          }
        }
      }
      rc.expctxWay = null
      rc.expctxNode = null
    }
  }

}
