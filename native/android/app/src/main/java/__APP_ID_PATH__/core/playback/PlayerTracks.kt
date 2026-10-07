package __APP_ID__.core.playback

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.session.MediaController

/** Subtitle selection on the live player. Ids are [SubtitleSource.id]; null means Off. */

fun MediaController.selectedSubtitleId(): String? {
    if (trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return null
    return currentTracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        ?.getTrackFormat(0)?.id
}

/** @return false if the requested track isn't present in the player (yet). */
fun MediaController.selectSubtitle(id: String?): Boolean {
    val params = trackSelectionParameters.buildUpon()
    if (id == null) {
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        val group = currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.getTrackFormat(0).id == id }
            ?: return false
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
    }
    trackSelectionParameters = params.build()
    return true
}

/** New video: subtitles start Off and any earlier choice is forgotten. */
fun MediaController.resetSubtitles() {
    trackSelectionParameters = trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()
}
