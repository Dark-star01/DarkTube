package __APP_ID__.core.playback

/**
 * Pure rule for removing duplicated subtitle cues. A cue is a duplicate ONLY if its text AND start
 * time AND end time are identical to a cue already kept. The same sentence at a different time is
 * a different cue and is always kept.
 */
object CueDedupe {
    data class Key(val text: String, val startUs: Long, val endUs: Long)

    /**
     * @param texts one entry per cue in a group that is shown from [startUs] to [endUs]; null = not a
     *   text cue (bitmap), always kept.
     * @param seen keys already emitted earlier in the same subtitle file (updated here).
     * @return indexes of [texts] to keep, in order.
     */
    fun keep(texts: List<String?>, startUs: Long, endUs: Long, seen: MutableSet<Key>): List<Int> {
        val kept = ArrayList<Int>(texts.size)
        texts.forEachIndexed { i, t ->
            if (t == null) {
                kept += i
            } else if (seen.add(Key(t, startUs, endUs))) {
                kept += i
            }
        }
        return kept
    }
}
