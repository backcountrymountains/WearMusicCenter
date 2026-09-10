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

/** Keeps the show name to roughly one line of the watch's 150dp-wide list item. */
private const val SHOW_NAME_MAX_CHARS = 22

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
                            .setEntryTitle(entryLabel(item))
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

            // Without this the item syncs opportunistically and the watch can wait
            // minutes for a list it asked for a moment ago.
            putDataRequest.setUrgent()

            Wearable.getDataClient(service).putDataItem(putDataRequest).await()
            Timber.d("Playlist: session-queue list delivered to the data layer")
        }

        /**
         * "Episode title" on line one, the show it belongs to on line two.
         *
         * The watch's round list item has no `maxLines`, so a second line renders
         * fine; the show name is shortened because the text view is only 150dp wide.
         * Falls back to the bare episode title when the app exposes no show name,
         * or repeats the episode title in it.
         */
        private fun entryLabel(item: MediaBrowserCompat.MediaItem): String {
            val episode = item.description.title?.toString()?.trim().orEmpty()

            val show = sequenceOf(item.description.subtitle, item.description.description)
                    .mapNotNull { it?.toString()?.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.equals(episode, ignoreCase = true) }
                    ?: return episode

            return "$episode\n${shorten(show)}"
        }

        /** Trims a show name to [SHOW_NAME_MAX_CHARS], preferring a word boundary. */
        private fun shorten(name: String): String {
            if (name.length <= SHOW_NAME_MAX_CHARS) return name

            val clipped = name.take(SHOW_NAME_MAX_CHARS)
            val lastSpace = clipped.lastIndexOf(' ')
            val stem = if (lastSpace >= SHOW_NAME_MAX_CHARS / 2) {
                clipped.substring(0, lastSpace)
            } else {
                clipped
            }
            return stem.trimEnd(' ', ',', '-', ':') + "…"
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
            putDataRequest.setUrgent()

            Wearable.getDataClient(service).putDataItem(putDataRequest).await()
            Timber.d("Playlist: list '%s' delivered to the data layer", listId)
        }
    }
}
