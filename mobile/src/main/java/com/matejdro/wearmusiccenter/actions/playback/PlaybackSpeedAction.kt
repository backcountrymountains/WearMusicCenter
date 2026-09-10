package com.matejdro.wearmusiccenter.actions.playback

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.PersistableBundle
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.actions.ActionHandler
import com.matejdro.wearmusiccenter.actions.SelectableAction
import com.matejdro.wearmusiccenter.common.CommPaths
import com.matejdro.wearmusiccenter.common.CustomLists
import com.matejdro.wearmusiccenter.music.MusicService
import com.matejdro.wearmusiccenter.proto.CustomList
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.text.DecimalFormat
import javax.inject.Inject
import kotlin.math.abs

/**
 * Opens a list of playback speeds on the watch. Picking one is applied to the
 * current media session through TransportControls.setPlaybackSpeed.
 *
 * Whether it does anything is up to the player: the session has to advertise
 * ACTION_SET_PLAYBACK_SPEED. AntennaPod does; many music players do not.
 */
class PlaybackSpeedAction : SelectableAction {
    constructor(context: Context) : super(context)
    constructor(context: Context, bundle: PersistableBundle) : super(context, bundle)

    override fun retrieveTitle(): String = context.getString(R.string.action_playback_speed)
    override val defaultIcon: Drawable
        get() = AppCompatResources.getDrawable(
                context,
                com.matejdro.common.R.drawable.action_playback_speed
        )!!

    class Handler @Inject constructor(private val service: MusicService) : ActionHandler<PlaybackSpeedAction> {
        override suspend fun handleAction(action: PlaybackSpeedAction) {
            val putDataRequest = PutDataRequest.create(CommPaths.DATA_CUSTOM_LIST)

            // These entries carry no icons. The playlist list writes one asset per
            // index, so clear the same range or its artwork lingers behind ours.
            SPEEDS.indices.forEach { putDataRequest.removeAsset(it.toString()) }

            val currentSpeed = service.currentMediaController?.playbackState?.playbackSpeed ?: 1f

            val entries = SPEEDS.map { speed ->
                val label = SPEED_FORMAT.format(speed) + "x"
                val isCurrent = abs(speed - currentSpeed) < SPEED_MATCH_TOLERANCE

                CustomList.ListEntry.newBuilder()
                        .setEntryId(speed.toString())
                        .setEntryTitle(if (isCurrent) "$label  <" else label)
                        .build()
            }

            val protoData = CustomList.newBuilder()
                    .addAllActions(entries)
                    .setListId(CustomLists.PLAYBACK_SPEED)
                    .setListTimestamp(System.currentTimeMillis())
                    .build()

            putDataRequest.data = protoData.toByteArray()

            // Without this the item syncs opportunistically and the watch can wait
            // minutes for a list it asked for a moment ago.
            putDataRequest.setUrgent()

            Wearable.getDataClient(service).putDataItem(putDataRequest).await()
            Timber.d("Speed: list delivered to the data layer")
        }
    }
}

/**
 * 1.0x to 2.1x in 0.1 steps — starts at normal speed so playback can always be put
 * back. Written out rather than generated so no float accumulates.
 */
private val SPEEDS = listOf(
        1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f,
        1.6f, 1.7f, 1.8f, 1.9f, 2.0f, 2.1f
)
private val SPEED_FORMAT = DecimalFormat("0.0#")
private const val SPEED_MATCH_TOLERANCE = 0.01f
