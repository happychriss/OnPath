package btools.router

class SuspectInfo {
  var prio = 0
  var triggers = 0

  companion object {
    const val TRIGGER_DEAD_END = 1
    const val TRIGGER_DEAD_START = 2
    const val TRIGGER_NODE_BLOCK = 4
    const val TRIGGER_BAD_ACCESS = 8
    const val TRIGGER_UNK_ACCESS = 16
    const val TRIGGER_SHARP_EXIT = 32
    const val TRIGGER_SHARP_ENTRY = 64
    const val TRIGGER_SHARP_LINK = 128
    const val TRIGGER_BAD_TR = 256

    fun addSuspect(map: MutableMap<Long, SuspectInfo>, id: Long, prio: Int, trigger: Int) {
      val iD: Long = id
      var info = map.get(iD)
      if (info == null) {
        info = SuspectInfo()
        map.put(iD, info)
      }
      info.prio = maxOf(info.prio, prio)
      info.triggers = info.triggers or trigger
    }

    fun addTrigger(old0: SuspectInfo?, prio: Int, trigger: Int): SuspectInfo {
      var old = old0
      if (old == null) {
        old = SuspectInfo()
      }
      old.prio = maxOf(old.prio, prio)
      old.triggers = old.triggers or trigger
      return old
    }

    fun getTriggerText(triggers: Int): String {
      val sb = StringBuilder()
      addText(sb, "dead-end", triggers, TRIGGER_DEAD_END)
      addText(sb, "dead-start", triggers, TRIGGER_DEAD_START)
      addText(sb, "node-block", triggers, TRIGGER_NODE_BLOCK)
      addText(sb, "bad-access", triggers, TRIGGER_BAD_ACCESS)
      addText(sb, "unkown-access", triggers, TRIGGER_UNK_ACCESS)
      addText(sb, "sharp-exit", triggers, TRIGGER_SHARP_EXIT)
      addText(sb, "sharp-entry", triggers, TRIGGER_SHARP_ENTRY)
      addText(sb, "sharp-link", triggers, TRIGGER_SHARP_LINK)
      addText(sb, "bad-tr", triggers, TRIGGER_BAD_TR)
      return sb.toString()
    }

    private fun addText(sb: StringBuilder, text: String, mask: Int, bit: Int) {
      if ((bit and mask) == 0) return
      if (sb.length > 0) sb.append(",")
      sb.append(text)
    }
  }

}
