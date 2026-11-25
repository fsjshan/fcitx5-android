/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import java.util.LinkedList

class PopupComponent :
    UniqueComponent<PopupComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val service by manager.inputMethodService()
    private val context by manager.context()
    private val theme by manager.theme()
    private val punctuation: PunctuationComponent by manager.must()

    private val showingEntryUi = HashMap<Int, PopupEntryUi>()
    private val dismissJobs = HashMap<Int, Job>()
    private val freeEntryUi = LinkedList<PopupEntryUi>()

    private val showingContainerUi = HashMap<Int, PopupContainerUi>()

    private val keyBottomMargin by lazy {
        context.dp(ThemeManager.prefs.keyVerticalMargin.getValue())
    }
    private val popupWidth by lazy {
        context.dp(38)
    }
    private val popupHeight by lazy {
        context.dp(116)
    }
    private val popupKeyHeight by lazy {
        context.dp(48)
    }
    private val popupRadius by lazy {
        context.dp(ThemeManager.prefs.keyRadius.getValue()).toFloat()
    }
    private val hideThreshold = 100L

    private val rootLocation = intArrayOf(0, 0)
    private val rootBounds: Rect = Rect()

    val root by lazy {
        context.frameLayout {
            // we want (0, 0) at top left
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = false
            isFocusable = false

            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val (x, y) = rootLocation.also { v.getLocationInWindow(it) }
                val width = right - left
                val height = bottom - top
                rootBounds.set(x, y, x + width, y + height)
            }
        }
    }

    /**
     * Cancel and remove the pending dismiss job of [viewId], if any.
     * Must be called whenever the popup entry of [viewId] is reused or recycled manually,
     * otherwise a stale job would recycle the entry a second time, putting the same
     * [PopupEntryUi] instance into [freeEntryUi] twice and eventually causing
     * "The specified child already has a parent" when it's polled by two different views.
     */
    private fun cancelDismissJob(viewId: Int) {
        dismissJobs.remove(viewId)?.cancel()
    }

    private fun showPopup(viewId: Int, content: String, bounds: Rect) {
        showingEntryUi[viewId]?.apply {
            cancelDismissJob(viewId)
            lastShowTime = System.currentTimeMillis()
            setText(content)
            return
        }
        val popup = (freeEntryUi.poll()
            ?: PopupEntryUi(context, theme, popupKeyHeight, popupRadius)).apply {
            lastShowTime = System.currentTimeMillis()
            setText(content)
        }
        // defensive: the instance may still be attached if it was recycled twice by mistake
        (popup.root.parent as? ViewGroup)?.removeView(popup.root)
        root.apply {
            add(popup.root, lParams(popupWidth, popupHeight) {
                // align popup bottom with key border bottom [^1]
                topMargin = bounds.bottom - popupHeight - keyBottomMargin
                leftMargin = (bounds.left + bounds.right - popupWidth) / 2
            })
        }
        showingEntryUi[viewId] = popup
    }

    private fun updatePopup(viewId: Int, content: String) {
        showingEntryUi[viewId]?.setText(content)
    }

    private fun showKeyboard(viewId: Int, keyboard: KeyDef.Popup.Keyboard, bounds: Rect) {
        val keys = PopupPreset[keyboard.label]
            ?: EmojiModifier.produceSkinTones(keyboard.label)
            ?: return
        // clear popup preview text         OR create empty popup preview
        showingEntryUi[viewId]?.also {
            // the entry is reused, so a pending dismiss job must not recycle it
            cancelDismissJob(viewId)
            it.lastShowTime = System.currentTimeMillis()
            it.setText("")
        } ?: showPopup(viewId, "", bounds)
        reallyShowKeyboard(viewId, keys, bounds)
    }

    private fun reallyShowKeyboard(viewId: Int, keys: Array<String>, bounds: Rect) {
        val labels = if (punctuation.enabled) {
            Array(keys.size) { punctuation.transform(keys[it]) }
        } else keys
        val keyboardUi = PopupKeyboardUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            popupRadius,
            popupWidth,
            popupKeyHeight,
            // position popup keyboard higher, because of [^1]
            popupHeight + keyBottomMargin,
            keys,
            labels
        )
        showPopupContainer(viewId, keyboardUi)
    }

    private fun showMenu(viewId: Int, menu: KeyDef.Popup.Menu, bounds: Rect) {
        showingEntryUi[viewId]?.let {
            // recycled manually here, so the pending job must not recycle it again
            cancelDismissJob(viewId)
            dismissPopupEntry(viewId, it)
        }
        val menuUi = PopupMenuUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            menu.items,
        )
        showPopupContainer(viewId, menuUi)
    }

    private fun showPopupContainer(viewId: Int, ui: PopupContainerUi) {
        // an old container of the same view may still be attached; detach it first
        dismissPopupContainer(viewId)
        root.apply {
            add(ui.root, lParams {
                leftMargin = ui.triggerBounds.left + ui.offsetX - rootBounds.left
                topMargin = ui.triggerBounds.top + ui.offsetY - rootBounds.top
            })
        }
        showingContainerUi[viewId] = ui
    }

    private fun changeFocus(viewId: Int, x: Float, y: Float): Boolean {
        return showingContainerUi[viewId]?.changeFocus(x, y) ?: false
    }

    private fun triggerFocused(viewId: Int): KeyAction? {
        return showingContainerUi[viewId]?.onTrigger()
    }

    private fun dismissPopup(viewId: Int) {
        dismissPopupContainer(viewId)
        showingEntryUi[viewId]?.also {
            // a dismiss job may already be scheduled (eg. DismissAction delivered twice
            // for the same gesture); never leave the old one running, or the entry would
            // be recycled twice into freeEntryUi
            cancelDismissJob(viewId)
            val timeLeft = it.lastShowTime + hideThreshold - System.currentTimeMillis()
            if (timeLeft <= 0L) {
                dismissPopupEntry(viewId, it)
            } else {
                dismissJobs[viewId] = service.lifecycleScope.launch {
                    delay(timeLeft)
                    dismissPopupEntry(viewId, it)
                    dismissJobs.remove(viewId)
                }
            }
        }
    }

    private fun dismissPopupContainer(viewId: Int) {
        showingContainerUi.remove(viewId)?.also {
            root.removeView(it.root)
        }
    }

    /**
     * Idempotent: only the call that actually removes [viewId] from [showingEntryUi]
     * is allowed to detach the view and return it to the object pool.
     */
    private fun dismissPopupEntry(viewId: Int, popup: PopupEntryUi) {
        if (showingEntryUi.remove(viewId) == null) return
        root.removeView(popup.root)
        if (!freeEntryUi.contains(popup)) {
            freeEntryUi.add(popup)
        }
    }

    fun dismissAll() {
        // avoid modifying collection while iterating
        dismissJobs.forEach { (_, job) ->
            job.cancel()
        }
        dismissJobs.clear()
        // too
        showingContainerUi.forEach { (_, container) ->
            root.removeView(container.root)
        }
        showingContainerUi.clear()
        // too too
        showingEntryUi.forEach { (_, entry) ->
            root.removeView(entry.root)
            if (!freeEntryUi.contains(entry)) {
                freeEntryUi.add(entry)
            }
        }
        showingEntryUi.clear()
    }

    val listener = PopupActionListener { action ->
        with(action) {
            when (this) {
                is PopupAction.ChangeFocusAction -> outResult = changeFocus(viewId, x, y)
                is PopupAction.DismissAction -> dismissPopup(viewId)
                is PopupAction.PreviewAction -> showPopup(viewId, content, bounds)
                is PopupAction.PreviewUpdateAction -> updatePopup(viewId, content)
                is PopupAction.ShowKeyboardAction -> showKeyboard(viewId, keyboard, bounds)
                is PopupAction.ShowMenuAction -> showMenu(viewId, menu, bounds)
                is PopupAction.TriggerAction -> outAction = triggerFocused(viewId)
            }
        }
    }
}
