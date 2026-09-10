package com.matejdro.wearmusiccenter.watch.view

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.support.wearable.input.WearableButtons
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.CurvingLayoutCallback
import androidx.wear.widget.WearableLinearLayoutManager
import androidx.wear.widget.WearableRecyclerView
import com.google.android.wearable.input.RotaryEncoderHelper
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.common.CustomLists
import com.matejdro.wearmusiccenter.common.MiscPreferences
import com.matejdro.wearmusiccenter.watch.communication.CustomListWithBitmaps
import com.matejdro.wearmusiccenter.watch.communication.WatchInfoSender
import com.matejdro.wearmusiccenter.watch.config.ButtonAction
import com.matejdro.wearutils.preferences.definition.Preferences
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs


/**
 * How visible an off-centre list item is. Kept well below the centred item's 1f so
 * white episode titles above and below do not compete with the selected one.
 */
private const val BACKGROUND_ITEM_ALPHA = 0.15f

/**
 * How large an off-centre row draws. Shrinking it is what actually stops long
 * episode titles from crowding the selected row; alpha alone left them overlapping.
 */
private const val BACKGROUND_ITEM_SCALE = 0.7f

/**
 * How far from the centre, as a fraction of the list height, a row has to travel to
 * reach full background dimming. Larger means a gentler ramp between the two states.
 */
private const val FOCUS_FALLOFF = 0.3f

/** Matches `textSize` in `item_list_action.xml`; both layout variants use it. */
private const val DEFAULT_LIST_TEXT_SIZE_SP = 18f

/** The speed list holds entries like "1.4x", so it gets the roomier size. */
private const val SPEED_LIST_TEXT_SIZE_SP = 28f

@SuppressLint("NotifyDataSetChanged")
@AndroidEntryPoint
class ActionsMenuFragment : Fragment() {
    private val viewmodel: MusicViewModel by activityViewModels()

    private lateinit var recycler: WearableRecyclerView
    private lateinit var recyclerClickDetector: RecyclerClickDetector

    private var menuItems: List<ButtonAction> = emptyList()
    private var customMenuItems: CustomListWithBitmaps? = null
    private lateinit var adapter: MenuAdapter
    private lateinit var layoutManager: MenuLayoutManager

    private var closeDrawerKeycode = -1

    private lateinit var activity: MainActivity

    private var alwaysPickCenter = false

    override fun onAttach(context: Context) {
        super.onAttach(context)

        activity = context as MainActivity

        viewmodel.actionsMenuConfig.config.observe(activity, actionItemsListener)
        viewmodel.preferences.observe(activity, preferencesListener)

        findButtons()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        recyclerClickDetector = inflater.inflate(R.layout.fragment_actions_list, container, false)
                as RecyclerClickDetector
        return recyclerClickDetector
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MenuAdapter()

        recycler = view.findViewById(R.id.recycler)

        layoutManager = MenuLayoutManager(requireContext())
        recycler.layoutManager = layoutManager
        recycler.isEdgeItemsCenteringEnabled = true
        recycler.adapter = adapter

        recyclerClickDetector.setOnClickListener {
            layoutManager.getCenterItem()?.let { index -> executeAction(index) }
        }

    }

    fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        if (RotaryEncoderHelper.isFromRotaryEncoder(ev) && WatchInfoSender.hasDiscreteRotaryInput()) {
            val moveForward = RotaryEncoderHelper.getRotaryAxisValue(ev) < 0

            val currentPosition = layoutManager.getCenterItem() ?: return true

            val targetPosition = if (moveForward) {
                (currentPosition + 1).coerceAtMost(adapter.itemCount - 1)
            } else {
                (currentPosition - 1).coerceAtLeast(0)
            }

            layoutManager.scrollToPosition(targetPosition)

            return true
        }
        return false
    }

    private val actionItemsListener = Observer<List<ButtonAction>?> {
        if (it == null) {
            return@Observer
        }

        menuItems = it
        adapter.notifyDataSetChanged()
    }

    private val preferencesListener = Observer<SharedPreferences?> {
        if (it == null) {
            return@Observer
        }

        alwaysPickCenter = Preferences.getBoolean(it, MiscPreferences.ALWAYS_SELECT_CENTER_ACTION)
        recyclerClickDetector.isClickable = alwaysPickCenter


        adapter.notifyDataSetChanged()
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun findButtons() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) {
            return
        }

        val numButtons = WearableButtons.getButtonCount(context)
        if (numButtons <= 1) {
            // We have at most 1 button. Lets use that lone button for confirm action
            return
        }

        //Find button with lowest Y value (furthest from user) - that will be close button
        closeDrawerKeycode = (KeyEvent.KEYCODE_STEM_1..KeyEvent.KEYCODE_STEM_3)
                .mapNotNull { WearableButtons.getButtonInfo(context, it) }
                .minByOrNull { it.y }?.keycode ?: -1
    }

    fun scrollToTop() {
        recycler.scrollToPosition(0)
    }

    fun refreshMenu(type: MenuType) {
        val newCustomMenuItems = when (type) {
            is MenuType.Actions -> null
            is MenuType.Custom -> type.items
        }

        if (newCustomMenuItems != customMenuItems) {
            customMenuItems = newCustomMenuItems
            adapter.notifyDataSetChanged()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == closeDrawerKeycode) {
            (context as MainActivity).closeMenuDrawer()
        } else {
            layoutManager.getCenterItem()?.let { executeAction(it) }
        }
        return true
    }

    private fun executeAction(index: Int) {
        val customList = customMenuItems
        if (customList != null) {
            viewmodel.executeItemFromCustomMenu(
                    customList.listId,
                    customList.items.elementAt(index).listItem.entryId
            )
        } else {
            viewmodel.executeActionFromMenu(index)
        }
    }

    /**
     * The speed list is short, icon-less entries like "1.4x", so it gets bigger text
     * packed tighter. Episode titles need the smaller, roomier default.
     */
    private fun styleRow(holder: MenuItemViewHolder, listId: String?) {
        if (listId == CustomLists.PLAYBACK_SPEED) {
            holder.applyStyle(SPEED_LIST_TEXT_SIZE_SP, compact = true)
        } else {
            holder.applyStyle(DEFAULT_LIST_TEXT_SIZE_SP, compact = false)
        }
    }

    inner class MenuAdapter : RecyclerView.Adapter<MenuItemViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuItemViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_list_action, parent, false)
            return MenuItemViewHolder(view)
        }

        override fun onBindViewHolder(holder: MenuItemViewHolder, position: Int) {
            val customMenuItems = customMenuItems
            if (customMenuItems != null) {
                val customItem = customMenuItems.items[position]

                holder.icon.setImageBitmap(customItem.icon)
                holder.title.text = customItem.listItem.entryTitle
                styleRow(holder, customMenuItems.listId)

                val clickListener = if (alwaysPickCenter) null else holder
                holder.itemView.setOnClickListener(clickListener)
                holder.itemView.isClickable = !alwaysPickCenter
            } else {
                val configItem = menuItems[position]

                holder.icon.setImageDrawable(configItem.icon)
                holder.title.text = configItem.title
                // Rows are recycled between lists, so reset rather than inherit
                // whatever styling the previous list asked for.
                styleRow(holder, null)

                val clickListener = if (alwaysPickCenter) null else holder
                holder.itemView.setOnClickListener(clickListener)
                holder.itemView.isClickable = !alwaysPickCenter
            }
        }

        override fun getItemCount(): Int {
            return customMenuItems?.items?.size ?: menuItems.size
        }

    }

    inner class MenuItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener {
        val icon: ImageView = itemView.findViewById(R.id.icon)
        val title: TextView = itemView.findViewById(R.id.text)

        /** The roomy row height from the layout, kept so compact rows can restore it. */
        private val defaultTitleMinHeight: Int = title.minHeight

        init {
            itemView.tag = this
        }

        /**
         * [compact] strips the row down to just its text: no icon gutter and no
         * minimum height. Entries like "1.4x" then sit as close together as the
         * text itself allows.
         */
        fun applyStyle(textSizeSp: Float, compact: Boolean) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            title.minHeight = if (compact) 0 else defaultTitleMinHeight
            icon.visibility = if (compact) View.GONE else View.VISIBLE
        }

        override fun onClick(v: View?) {
            activity.buzz()
            executeAction(bindingAdapterPosition)
        }
    }

    private inner class MenuLayoutManager(context: Context) : WearableLinearLayoutManager(context) {
        private val roundScreen: Boolean = resources.configuration.isScreenRound

        private var closestDistToCenter = Float.MAX_VALUE
        private var closestChildViewHolder: MenuItemViewHolder? = null

        fun getCenterItem(): Int? {
            val closestChildViewHolder = closestChildViewHolder
                    ?: return null

            return closestChildViewHolder.bindingAdapterPosition
        }

        /**
         * 1 for a row sitting at the centre of the list, easing to 0 by
         * [FOCUS_FALLOFF] of the list height away from it.
         *
         * Derived from the row's current position on every pass rather than cached,
         * so it cannot drift out of step with what is on screen.
         */
        private fun focusFactor(child: View): Float {
            if (height == 0) return 0f

            val centerOffset = child.height.toFloat() / 2f / height.toFloat()
            val yRelativeToCenter = child.y / height + centerOffset
            val distance = abs(0.5f - yRelativeToCenter)

            return (1f - distance / FOCUS_FALLOFF).coerceIn(0f, 1f)
        }

        private fun lerp(from: Float, to: Float, fraction: Float): Float =
                from + (to - from) * fraction

        private fun prepareChildrenLayout() {
            closestDistToCenter = Float.MAX_VALUE
            closestChildViewHolder = null
        }

        private fun finishChildrenLayout() {
            for (childIndex in 0 until childCount) {
                val child = getChildAt(childIndex)
                val holder = child!!.tag as MenuItemViewHolder

                // Continuous, so a row fades and grows as it travels towards the
                // centre instead of snapping the moment it becomes the closest one.
                val focus = focusFactor(child)

                val itemScale = lerp(BACKGROUND_ITEM_SCALE, 1f, focus)

                child.alpha = lerp(BACKGROUND_ITEM_ALPHA, 1f, focus)
                // Anchored right when unfocused so it shrinks away from the selected
                // row, sliding to a left anchor as it takes focus.
                child.pivotX = child.width * (1f - focus)
                // Scale is absolute, so unlike a translation it cannot accumulate
                // across layout passes.
                child.scaleX = itemScale
                child.scaleY = itemScale

                if (roundScreen) {
                    val imageScale = lerp(0.75f, 1f, focus)
                    holder.icon.scaleX = imageScale
                    holder.icon.scaleY = imageScale
                }

            }
        }

        override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
            prepareChildrenLayout()
            val ret = super.scrollVerticallyBy(dy, recycler, state)
            finishChildrenLayout()
            return ret
        }

        override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
            prepareChildrenLayout()
            super.onLayoutChildren(recycler, state)
            finishChildrenLayout()
        }

        private val childLayoutCallback = object : CurvingLayoutCallback(context) {
            override fun onLayoutFinished(child: View, parent: RecyclerView?) {
                super.onLayoutFinished(child, parent)

                // Figure out % progress from top to bottom
                val centerOffset = child.height.toFloat() / 2.0f / recycler.height.toFloat()
                val yRelativeToCenterOffset = child.y / recycler.height + centerOffset

                // Normalize for center
                val progressToCenter = abs(0.5f - yRelativeToCenterOffset)

                val holder = child.tag as MenuItemViewHolder

                if (closestDistToCenter > progressToCenter) {
                    closestChildViewHolder = holder
                    closestDistToCenter = progressToCenter
                }
            }

        }

        init {
            layoutCallback = childLayoutCallback
        }
    }

    sealed class MenuType {
        object Actions : MenuType()
        class Custom(val items: CustomListWithBitmaps) : MenuType()
    }
}
