package btools.util

interface ProgressListener {
    fun updateProgress(task: String, progress: Int)

    fun isCanceled(): Boolean
}
