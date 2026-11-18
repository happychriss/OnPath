// Port of btools.util.ProgressListener (BRouter 1.7.11-beta, MIT)
package btools.util

interface ProgressListener {
  fun updateProgress(task: String, progress: Int)

  fun isCanceled(): Boolean
}
