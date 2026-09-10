package com.matejdro.wearmusiccenter.actions

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.PersistableBundle
import android.support.v4.media.MediaBrowserCompat
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.common.CommPaths
import com.matejdro.wearmusiccenter.common.CustomLists
import com.matejdro.wearmusiccenter.config.buttons.ConfigConstants
import com.matejdro.wearmusiccenter.music.MediaBrowseClient
import com.matejdro.wearmusiccenter.music.MusicService
import com.matejdro.wearmusiccenter.proto.CustomList
import com.matejdro.wearutils.miscutils.BitmapUtils
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject

class OpenPlaylistAction : SelectableAction {
    constructor(context: Context) : super(context)
    constructor(context: Context, bundle: PersistableBundle) : super(context, bundle)

    override fun retrieveTitle(): String = context.getString(R.string.open_playlist_menu)
    override val defaultIcon: Drawable
        get() = AppCompatResources.getDrawable(context, com.matejdro.common.R.drawable.action_open_playlist)!!

    class Handler @Inject constructor(private val service: MusicService) : ActionHandler<OpenPlaylistAction> {
        override suspend fun handleAction(action: OpenPlaylistAction) {
            // TODO take density of Watch's display into account
            val targetIconSize = (ConfigConstants.BUTTON_ICON_SIZE_DP)

            val sessionQueue = service.currentMediaController?.queue
            Timber.d("Playlist: session queue is %s (%d items)",
                    if (sessionQueue == null) "null" else "present", sessionQueue?.size ?: 0)

            // Prefer the playing app's browse tree. Podcast apps in particular keep
            // their real queue there and report a 0-1 item session queue.
            val browsedQueue = loadBrowsedQueue()
            if (browsedQueue.isNotEmpty()) {
                sendList(browsedQueue.take(20).map { item ->
                    CustomList.ListEntry.newBuilder()
                            .setEntryId(item.mediaId ?: "")
                            .setEntryTitle(item.description.title?.toString() ?: "")
                            .build()
                }, CustomLists.BROWSED_QUEUE)
                return
            }

            val playlist = sessionQueue?.take(20)
            val putDataRequest = PutDataRequest.create(CommPaths.DATA_CUSTOM_LIST)

            // An empty (not null) queue used to produce a zero-entry list, which the
            // watch renders as a blank drawer. Treat it like "not supported".
            val protoList = if (!playlist.isNullOrEmpty()) {
                playlist.mapIndexed { index, queueItem ->
                    var icon = queueItem.description.iconBitmap
                            ?: queueItem.description.iconUri?.let {
                                BitmapUtils.getBitmap(BitmapUtils.getDrawableFromUri(service, it))
                            }

                    icon = BitmapUtils.shrinkPreservingRatio(
                            icon,
                            targetIconSize,
                            targetIconSize,
                            true
                    )


                    val iconData = BitmapUtils.serialize(icon)
                    if (iconData != null) {
                        putDataRequest.putAsset(index.toString(), Asset.createFromBytes(iconData))
                    } else {
                        putDataRequest.removeAsset(index.toString())
                    }

                    CustomList.ListEntry.newBuilder()
                            .setEntryId(queueItem.queueId.toString())
                            .setEntryTitle(queueItem.description.title?.toString() ?: "")
                            .build()
                }
            } else {
                putDataRequest.removeAsset("0")
                listOf(
                        CustomList.ListEntry.newBuilder()
                                .setEntryId(CustomLists.SPECIAL_ITEM_ERROR)
                                .setEntryTitle(service.getString(R.string.error_playlist_not_supported))
                                .build()
                )

            }

            val protoData = CustomList.newBuilder()
                    .addAllActions(protoList)
                    .setListId(CustomLists.PLAYLIST)
                    .setListTimestamp(System.currentTimeMillis())
                    .build()

            putDataRequest.data = protoData.toByteArray()

            Wearable.getDataClient(service).putDataItem(putDataRequest).await()
        }

        /**
         * Best-effort read of the playing app's browse tree. Empty on any failure —
         * the caller falls back to the session queue.
         */
        private suspend fun loadBrowsedQueue(): List<MediaBrowserCompat.MediaItem> {
            val playingPackage = service.currentMediaController?.packageName
            if (playingPackage == null) {
                Timber.d("Playlist: no active media controller to browse")
                return emptyList()
            }

            val client = MediaBrowseClient(service)
            val component = client.findBrowseService(playingPackage) ?: return emptyList()

            Timber.d("Playlist: browsing %s", component)
            return client.loadQueue(component)
        }

        /** Pushes an already-built list of entries to the watch, without icons. */
        private suspend fun sendList(entries: List<CustomList.ListEntry>, listId: String) {
            Timber.d("Playlist: sending %d entries as list '%s'", entries.size, listId)

            val putDataRequest = PutDataRequest.create(CommPaths.DATA_CUSTOM_LIST)
            entries.indices.forEach { putDataRequest.removeAsset(it.toString()) }

            val protoData = CustomList.newBuilder()
                    .addAllActions(entries)
                    .setListId(listId)
                    .setListTimestamp(System.currentTimeMillis())
                    .build()

            putDataRequest.data = protoData.toByteArray()

            Wearable.getDataClient(service).putDataItem(putDataRequest).await()
        }
    }
}
