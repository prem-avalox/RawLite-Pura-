package com.example.rawlite

/** Small bounded rolling buffer for decoded review frames. All calls are made on the UI thread. */
internal class SequenceFrameBuffer<T>(
    private val capacity: Int,
    private val release: (T) -> Unit
) {
    // Playback is sequential: insertion order evicts already-consumed frames before prefetched
    // future frames. Access-order LRU would retain displayed frames and punch holes ahead.
    private val frames = LinkedHashMap<Int, T>(capacity, 0.75f, false)

    init {
        require(capacity > 0)
    }

    operator fun get(index: Int): T? = frames[index]

    fun put(index: Int, frame: T, protectedFrame: T? = null) {
        frames.put(index, frame)?.takeIf { it !== frame && it !== protectedFrame }?.let(release)
        trim(protectedFrame)
    }

    fun contains(index: Int): Boolean = frames.containsKey(index)

    fun missingWindow(startIndex: Int, frameCount: Int, totalFrames: Int): List<Int> {
        if (totalFrames <= 0 || frameCount <= 0) return emptyList()
        val start = startIndex.coerceIn(0, totalFrames - 1)
        val endExclusive = (start + frameCount).coerceAtMost(totalFrames)
        return (start until endExclusive).filterNot(frames::containsKey)
    }

    fun contiguousReadyCount(startIndex: Int, totalFrames: Int): Int {
        if (totalFrames <= 0) return 0
        val start = startIndex.coerceIn(0, totalFrames - 1)
        var count = 0
        for (index in start until totalFrames) {
            if (!frames.containsKey(index)) break
            count++
        }
        return count
    }

    fun clear(protectedFrame: T? = null) {
        frames.values.distinct().forEach { if (it !== protectedFrame) release(it) }
        frames.clear()
    }

    private fun trim(protectedFrame: T?) {
        while (frames.size > capacity) {
            val iterator = frames.entries.iterator()
            var removed = false
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value === protectedFrame) continue
                iterator.remove()
                release(entry.value)
                removed = true
                break
            }
            if (!removed) break
        }
    }
}
