/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.broadcast

import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must

class PreeditEmptyStateComponent :
    UniqueComponent<PreeditEmptyStateComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val fcitx by manager.fcitx()
    private val broadcaster: InputBroadcaster by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    var isEmpty: Boolean = true
        private set

    // 缓存上次已知的 preedit 状态，避免每次都通过 runImmediately 读 fcitx 缓存（有锁竞争）
    private var lastClientPreeditEmpty: Boolean = true
    private var lastPreeditEmpty: Boolean = true

    fun updatePreeditEmptyState(
        clientPreedit: FormattedText = fcitx.runImmediately { clientPreeditCached },
        preedit: FormattedText = fcitx.runImmediately { inputPanelCached.preedit }
    ) {
        val empty = clientPreedit.isEmpty() && preedit.isEmpty()
        if (isEmpty == empty) return
        isEmpty = empty
        broadcaster.onPreeditEmptyStateUpdate(isEmpty)
        returnKeyDrawable.updateDrawableOnPreedit(isEmpty)
    }

    // 直接用已知的空/非空状态更新，避免 runImmediately 锁竞争
    fun updatePreeditEmptyStateByKnown(clientPreeditEmpty: Boolean? = null, preeditEmpty: Boolean? = null) {
        if (clientPreeditEmpty != null) lastClientPreeditEmpty = clientPreeditEmpty
        if (preeditEmpty != null) lastPreeditEmpty = preeditEmpty
        val empty = lastClientPreeditEmpty && lastPreeditEmpty
        if (isEmpty == empty) return
        isEmpty = empty
        broadcaster.onPreeditEmptyStateUpdate(isEmpty)
        returnKeyDrawable.updateDrawableOnPreedit(isEmpty)
    }
}
