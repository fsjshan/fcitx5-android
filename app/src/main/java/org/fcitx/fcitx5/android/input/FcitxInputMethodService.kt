/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.LruCache
import android.util.Size
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.cursor.CursorRange
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.utils.InputMethodUtil
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.forceShowSelf
import org.fcitx.fcitx5.android.utils.inputMethodManager
import org.fcitx.fcitx5.android.utils.monitorCursorAnchor
import org.fcitx.fcitx5.android.utils.styledFloat
import org.fcitx.fcitx5.android.utils.withBatchEdit
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import splitties.resources.styledColor
import timber.log.Timber
import kotlin.math.max

class FcitxInputMethodService : LifecycleInputMethodService() {

    private lateinit var fcitx: FcitxConnection

    private var jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private val cachedKeyEvents = LruCache<Int, KeyEvent>(78)
    private var cachedKeyEventIndex = 0

    private lateinit var pkgNameCache: PackageNameCache

    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null

    private val navbarMgr = NavigationBarManager()
    private val inputDeviceMgr = InputDeviceManager onChange@{
        val w = window.window ?: return@onChange
        navbarMgr.evaluate(w, useVirtualKeyboard = it)
    }

    private var capabilityFlags = CapabilityFlags.DefaultFlags

    private val selection = CursorTracker()

    val currentInputSelection: CursorRange
        get() = selection.latest

    fun predictSelection(start: Int, end: Int = start) {
        selection.predict(start, end)
    }

    /**
     * 【Fix】顶部框是否为光标权威来源。
     *
     * 权威期间目标框上报的滞后光标不得覆盖顶部框光标。
     */
    fun isTopEditTextAuthoritative(): Boolean = inputView?.isTopEditTextAuthoritative() == true

    /**
     * 【Fix】以顶部 EditText 的光标为准，重新对齐本端 [selection] 与目标框光标。
     *
     * 目标 App 输入框在收到 setSelection / commitText 后，常常回调一个滞后或被改写的
     * onUpdateSelection（例如把光标放在插入点+1、或干脆忽略 setSelection），
     * 若本端一直采信这些回调，下一次插入就会落在错误位置，
     * 表现为"只有第一个字符插对，后续追加到末尾"和"输入过程中光标跳变"。
     *
     * 因此在每次提交/删除之后，用顶部框的真实光标覆盖 [selection]。
     *
     * 【Fix2】调用点都在 [syncToTopEditText] 的 post 之前，此刻顶部框内容可能还没更新，
     * 因此这里只更新 [selection]，真正的目标框 setSelection 推迟到 post 里，
     * 等顶部框内容刷新后再对齐一次，保证下一次插入点正确。
     */
    fun alignSelectionToTopEditText() {
        val view = inputView ?: return
        if (isPasswordInputType()) return
        val (start, end) = view.getTopEditTextSelection()
        selection.resetTo(start, end)
        Timber.v("alignSelectionToTopEditText: ($start,$end)")
        // 顶部框内容由 syncToTopEditText 的 post 更新，等它跑完再对齐一次。
        // 用 view.post 保证排在 syncToTopEditText 投递的任务之后。
        // 目标框的 setSelection 只在这里做一次，避免同一帧内两次 Binder 调用。
        view.post { alignSelectionToTopEditTextNow() }
    }

    /**
     * 立即用顶部框光标对齐 [selection] 与目标框光标（不做 post，供 post 块内部调用）。
     *
     * 【Fix】setSelection 前先 predictSelection，让目标框随后的 onUpdateSelection 命中预测，
     * 避免被 CursorTracker 当成"用户输入"而触发 fcitx focus(false)/focus(true) 重置；
     * 同时标记"已发出"，使紧随其后的回声回调不会把 pending 拒掉。
     */
    private fun alignSelectionToTopEditTextNow() {
        val view = inputView ?: return
        if (isPasswordInputType()) return
        val (start, end) = view.getTopEditTextSelection()
        selection.resetTo(start, end)
        val ic = currentInputConnection ?: return
        try {
            selection.predict(start, end)
            ic.setSelection(start, end)
            view.markTopCursorSyncIssued()
        } catch (e: Exception) {
            Timber.w("alignSelectionToTopEditTextNow: setSelection failed: ${e.message}")
        }
    }

    val isComposing: Boolean
        get() = composing.isNotEmpty()

    private val composing = CursorRange()
    private var composingText = FormattedText.Empty

    // 【Fix】原 isPasswordField / plaintextPassword 字段已删除（无任何读写点），
    // 密码框状态统一由 cachedPlaintextPassword / passwordCursorPosition 维护。

    private fun resetComposingState() {
        composing.clear()
        composingText = FormattedText.Empty
    }

    private var cursorUpdateIndex: Int = 0

    private var highlightColor: Int = 0x66008577 // material_deep_teal_500 with alpha 0.4

    private val prefs = AppPrefs.getInstance()
    private val inlineSuggestions by prefs.keyboard.inlineSuggestions
    private val ignoreSystemCursor by prefs.advanced.ignoreSystemCursor

    private val recreateInputViewPrefs: Array<ManagedPreference<*>> = arrayOf(
        prefs.keyboard.expandKeypressArea,
        prefs.advanced.disableAnimation,
        prefs.advanced.ignoreSystemWindowInsets,
    )

    private fun replaceInputView(theme: Theme): InputView {
        val newInputView = InputView(this, fcitx, theme)
        setInputView(newInputView)
        inputDeviceMgr.setInputView(newInputView)
        navbarMgr.setupInputView(newInputView)
        inputView = newInputView
        return newInputView
    }

    private fun replaceCandidateView(theme: Theme): CandidatesView {
        val newCandidatesView = CandidatesView(this, fcitx, theme)
        // replace CandidatesView manually
        contentView.removeView(candidatesView)
        // put CandidatesView directly under content view
        contentView.addView(newCandidatesView)
        inputDeviceMgr.setCandidatesView(newCandidatesView)
        navbarMgr.setupInputView(newCandidatesView)
        candidatesView = newCandidatesView
        return newCandidatesView
    }

    private fun replaceInputViews(theme: Theme) {
        navbarMgr.evaluate(window.window!!)
        replaceInputView(theme)
        replaceCandidateView(theme)
    }

    @Keep
    private val recreateInputViewListener = ManagedPreference.OnChangeListener<Any> { _, _ ->
        replaceInputView(ThemeManager.activeTheme)
    }

    @Keep
    private val recreateCandidatesViewListener = ManagedPreferenceProvider.OnChangeListener {
        replaceCandidateView(ThemeManager.activeTheme)
    }

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        replaceInputViews(it)
    }

    /**
     * Post a fcitx operation to [jobs] to be executed
     *
     * Unlike `fcitx.runOnReady` or `fcitx.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postFcitxJob] ensures that operations are executed sequentially.
     */
    fun postFcitxJob(block: suspend FcitxAPI.() -> Unit): Job {
        val job = fcitx.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            fcitx.runOnReady(block)
        }
        jobs.trySend(job)
        Timber.v("[IMS] postFcitxJob: job enqueued")
        return job
    }

    override fun onCreate() {
        Timber.i("[IMS] onCreate: connecting to FcitxDaemon")
        fcitx = FcitxDaemon.connect(javaClass.name)
        Timber.i("[IMS] onCreate: FcitxDaemon.connect done, starting jobs consumer and event collector")
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            fcitx.runImmediately { eventFlow }.collect {
                handleFcitxEvent(it)
            }
        }
        pkgNameCache = PackageNameCache(this)
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
       ThemeManager.addOnChangedListener(onThemeChangeListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Timber.d("[IMS] onCreate: API34+, posting SubtypeManager.syncWith job")
            postFcitxJob {
                SubtypeManager.syncWith(enabledIme())
            }
        }
        // 强制写入 pinyin 性能优化配置（首次或版本升级时）。
        // 通过版本号避免每次启动重复写入，也不会干扰用户之后在设置界面的手动修改。
        // 当前版本 = 3：Prediction=False, PredictionSize=5, PageSize=5,
        //              SpellEnabled=False, SymbolsEnabled=False, Number of sentence=1,
        //              VAsQuickphrase=False（关闭 V 键触发快速输入）
        val PINYIN_PERF_CONFIG_VERSION = 3
        if (prefs.internal.pinyinPerfConfigVersion.getValue() < PINYIN_PERF_CONFIG_VERSION) {
            postFcitxJob {
                applyPinyinPerfConfig()
            }
            prefs.internal.pinyinPerfConfigVersion.setValue(PINYIN_PERF_CONFIG_VERSION)
            Timber.i("[IMS] onCreate: scheduled pinyin perf config update to v$PINYIN_PERF_CONFIG_VERSION")
        }
        super.onCreate()
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        // 【Fix】在 onCreate 完成后立即预热 InputView（包含 TextKeyboard 构造），
        // 让 GC 压力在用户点击输入框前就已释放，而不是等 onCreateInputView 再构建。
        // 使用 postDelayed 100ms 让系统初始化先稳定，再在主线程空闲时执行。
        android.os.Handler(mainLooper).postDelayed({
            if (inputView == null) {
                Timber.d("[IMS] onCreate: pre-warming InputView in background")
                replaceInputViews(ThemeManager.activeTheme)
            }
        }, 100L)
        Timber.i("[IMS] onCreate: done")
    }

    private fun handleFcitxEvent(event: FcitxEvent<*>) {
        when (event) {
            is FcitxEvent.CommitStringEvent -> {
                Timber.i("[IMS] handleFcitxEvent: CommitStringEvent text='${event.data.text}' cursor=${event.data.cursor}")
                commitText(event.data.text, event.data.cursor)
            }
            is FcitxEvent.KeyEvent -> event.data.let event@{
                if (it.states.virtual) {
                    // KeyEvent from virtual keyboard
                    Timber.d("[IMS] handleFcitxEvent: KeyEvent(virtual) sym=${it.sym} unicode=${it.unicode} up=${it.up}")
                    when (it.sym.sym) {
                        FcitxKeyMapping.FcitxKey_BackSpace -> handleBackspaceKey()
                        FcitxKeyMapping.FcitxKey_Return -> handleReturnKey()
                        FcitxKeyMapping.FcitxKey_Left -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
                        FcitxKeyMapping.FcitxKey_Right -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                        else -> if (it.unicode > 0) {
                            commitText(Character.toString(it.unicode))
                        } else {
                            Timber.w("[IMS] handleFcitxEvent: Unhandled Virtual KeyEvent: $it")
                        }
                    }
                } else {
                    // KeyEvent from physical keyboard (or input method engine forwardKey)
                    Timber.d("[IMS] handleFcitxEvent: KeyEvent(physical) sym=${it.sym} up=${it.up} timestamp=${it.timestamp}")
                    // use cached event if available
                    cachedKeyEvents.remove(it.timestamp)?.let { keyEvent ->
                        Timber.d("[IMS] handleFcitxEvent: forwarding cached KeyEvent ts=${it.timestamp}")
                        currentInputConnection?.sendKeyEvent(keyEvent)
                        return@event
                    }
                    // simulate key event
                    val keyCode= it.sym.keyCode
                    if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                        // recognized keyCode
                        val eventTime = SystemClock.uptimeMillis()
                        Timber.d("[IMS] handleFcitxEvent: simulating keyCode=$keyCode up=${it.up}")
                        if (it.up) {
                            sendUpKeyEvent(eventTime, keyCode, it.states.metaState)
                        } else {
                            sendDownKeyEvent(eventTime, keyCode, it.states.metaState)
                        }
                    } else {
                        // no matching keyCode, commit character once on key down
                        if (!it.up && it.unicode > 0) {
                            Timber.d("[IMS] handleFcitxEvent: unknown keyCode, committing unicode=${it.unicode}")
                            commitText(Character.toString(it.unicode))
                        } else {
                            Timber.w("[IMS] handleFcitxEvent: Unhandled Fcitx KeyEvent: $it")
                        }
                    }
                }
            }
            is FcitxEvent.ClientPreeditEvent -> {
                Timber.d("[IMS] handleFcitxEvent: ClientPreeditEvent preedit='${event.data}' cursor=${event.data.cursor}")
                updateComposingText(event.data)
            }
            is FcitxEvent.DeleteSurroundingEvent -> {
                val (before, after) = event.data
                Timber.d("[IMS] handleFcitxEvent: DeleteSurroundingEvent before=$before after=$after")
                handleDeleteSurrounding(before, after)
            }
            is FcitxEvent.IMChangeEvent -> {
                Timber.i("[IMS] handleFcitxEvent: IMChangeEvent ime=${event.data.uniqueName}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val im = event.data.uniqueName
                    val subtype = SubtypeManager.subtypeOf(im) ?: return
                    skipNextSubtypeChange = im
                    Timber.d("[IMS] handleFcitxEvent: IMChangeEvent API34+, switchInputMethod to subtype=${subtype.languageTag}")
                    // [^1]: notify system that input method subtype has changed
                    switchInputMethod(InputMethodUtil.componentName, subtype)
                }
            }
            else -> {}
        }
    }

    private fun handleDeleteSurrounding(before: Int, after: Int) {
        val ic = currentInputConnection ?: return

        // 【Fix】密码框走专用路径：删除位置只认明文缓存 passwordCursorPosition，
        // 且删除后必须按明文缓存刷新顶部框（否则顶部框内容不更新）。
        if (isPasswordInputType()) {
            handleDeleteSurroundingInPassword(before, after)
            return
        }

        // 【Fix】与 commitText 同理：先按本端记录的 selection 定位目标框光标，再删除，
        // 避免目标框 selection 与顶部 EditText 不一致时删错位置。
        // 注意 predictOffset 会改变 selection.latest，所以必须在 predict 之前取定位点。
        val anchor = selection.latest
        ic.withBatchEdit {
            if (anchor.isEmpty()) {
                val len = (getTextBeforeCursor(1000, 0)?.length ?: 0) +
                        (getTextAfterCursor(1000, 0)?.length ?: 0)
                if (anchor.start in 0..len) {
                    setSelection(anchor.start, anchor.start)
                }
            }
            if (before > 0) {
                selection.predictOffset(-before)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                deleteSurroundingTextInCodePoints(before, after)
            } else {
                deleteSurroundingText(before, after)
            }
        }
        // 【Fix】删除后顶部框光标才是权威，用它覆盖本端 selection，
        // 避免目标框回调的滞后光标把下一次插入点带偏。
        // 顺序在 syncToTopEditText 之前，保证两次 post 的 FIFO 顺序。
        alignSelectionToTopEditText()
        // 同步到顶端EditText
        syncToTopEditText()
    }

    /**
     * 【Fix】密码框专用 deleteSurrounding 路径（明文缓存坐标驱动）。
     */
    private fun handleDeleteSurroundingInPassword(before: Int, after: Int) {
        val ic = currentInputConnection
        // 【Fix】删除前先取明文光标，掩码定位点必须由它换算，不能删完再取（那时已被改成 start）。
        val plainCursor = passwordCursorPosition
        val plainLen = cachedPlaintextPassword.length
        val plainStart = (plainCursor - before).coerceIn(0, plainLen)
        val plainEnd = (plainCursor + after).coerceIn(plainStart, plainLen)
        // 1. 更新明文缓存
        deleteTextFromPassword(before, after)
        val cursor = passwordCursorPosition
        // 2. 目标框按掩码坐标删除
        if (ic != null) {
            try {
                ic.withBatchEdit {
                    val len = (getTextBeforeCursor(1000, 0)?.length ?: 0) +
                            (getTextAfterCursor(1000, 0)?.length ?: 0)
                    // 【Fix】明文坐标 → 掩码坐标换算（两者长度可能不等）
                    val ratio = if (plainLen > 0) len.toFloat() / plainLen.toFloat() else 1f
                    val maskStart = kotlin.math.floor(plainStart * ratio).toInt().coerceIn(0, len)
                    val maskEnd = kotlin.math.floor(plainEnd * ratio).toInt().coerceIn(maskStart, len)
                    val maskBefore = maskStart
                    val maskAfter = (maskEnd - maskStart).coerceAtLeast(0)
                    setSelection(maskStart, maskStart)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        deleteSurroundingTextInCodePoints(maskBefore, maskAfter)
                    } else {
                        deleteSurroundingText(maskBefore, maskAfter)
                    }
                    // 【Fix】删除后重新读取掩码长度，再按新的明文/掩码比例换算光标
                    val newLen = (getTextBeforeCursor(1000, 0)?.length ?: 0) +
                            (getTextAfterCursor(1000, 0)?.length ?: 0)
                    val newRatio = if (cachedPlaintextPassword.isNotEmpty())
                        newLen.toFloat() / cachedPlaintextPassword.length.toFloat() else 1f
                    val maskTarget = kotlin.math.floor(cursor * newRatio).toInt()
                        .coerceIn(0, maxOf(newLen, 0))
                    // 【Fix】先 predict 再 setSelection，理由同 commitTextInPassword
                    selection.predict(maskTarget)
                    setSelection(maskTarget, maskTarget)
                }
            } catch (e: Exception) {
                Timber.w("handleDeleteSurroundingInPassword: failed to delete: ${e.message}")
            }
        }
        Timber.d("handleDeleteSurroundingInPassword: before=$before after=$after cursor=$cursor")
        // 3. 顶部框按明文缓存刷新
        inputView?.refreshPasswordTopEditText()
    }

    private fun handleBackspaceKey() {
        // 【Fix】密码框走专用路径：目标框是掩码，selection 里的坐标来自掩码文本，
        // 与明文缓存位置不可映射；删除位置只认 passwordCursorPosition。
        if (isPasswordInputType()) {
            handlePasswordBackspaceKey()
            return
        }
        val lastSelection = selection.latest
        if (lastSelection.isNotEmpty()) {
            selection.predict(lastSelection.start)
        } else if (lastSelection.start > 0) {
            selection.predictOffset(-1)
        }

        // In practice nobody (apart form ourselves) would set `privateImeOptions` to our
        // `DeleteSurroundingFlag`, leading to a behavior of simulating backspace key pressing
        // in almost every EditText.
        if (currentInputEditorInfo.privateImeOptions != DeleteSurroundingFlag ||
            currentInputEditorInfo.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        ) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            // 【Fix】先对齐本端 selection，再投递顶部框内容同步（post FIFO 顺序）
            alignSelectionToTopEditText()
            // 同步到顶端EditText
            syncToTopEditText()
            return
        }
        if (lastSelection.isEmpty()) {
            if (lastSelection.start <= 0) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                // 【Fix】先对齐本端 selection，再投递顶部框内容同步（post FIFO 顺序）
                alignSelectionToTopEditText()
                // 同步到顶端EditText
                syncToTopEditText()
                return
            }
            currentInputConnection.withBatchEdit {
                // 【Fix】先按本端 selection 定位目标框光标，再删除，避免删错位置
                val len = (getTextBeforeCursor(1000, 0)?.length ?: 0) +
                        (getTextAfterCursor(1000, 0)?.length ?: 0)
                if (lastSelection.start in 0..len) {
                    setSelection(lastSelection.start, lastSelection.start)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    deleteSurroundingTextInCodePoints(1, 0)
                } else {
                    deleteSurroundingText(1, 0)
                }
            }
        } else {
            currentInputConnection.withBatchEdit {
                val len = (getTextBeforeCursor(1000, 0)?.length ?: 0) +
                        (getTextAfterCursor(1000, 0)?.length ?: 0)
                if (lastSelection.start in 0..len && lastSelection.end <= len) {
                    setSelection(lastSelection.start, lastSelection.end)
                }
                commitText("", 0)
            }
        }
        // 【Fix】先对齐本端 selection，再投递顶部框内容同步（post FIFO 顺序）
        alignSelectionToTopEditText()
        // 同步到顶端EditText
        syncToTopEditText()
    }
    
    /**
     * 【Fix】密码框专用退格路径。
     *
     * 删除位置只认明文缓存 [passwordCursorPosition]（顶部框明文坐标），
     * 目标框按同一位置（掩码坐标，需收敛）删除，最后按明文缓存刷新顶部框。
     */
    private fun handlePasswordBackspaceKey() {
        val ic = currentInputConnection
        val cursor = passwordCursorPosition
        val plainLen = cachedPlaintextPassword.length
        if (cursor <= 0 || plainLen == 0) {
            // 明文缓存已空：仍把 DEL 发给目标框，保证行为与系统一致
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            inputView?.refreshPasswordTopEditText()
            return
        }
        // 1. 更新明文缓存
        cachedPlaintextPassword = cachedPlaintextPassword.removeRange(cursor - 1, cursor)
        passwordCursorPosition = cursor - 1
        Timber.d("Password backspace: removed char at ${cursor - 1}, cursor=$passwordCursorPosition")
        // 2. 目标框按掩码坐标删除
        if (ic != null) {
            try {
                ic.withBatchEdit {
                    val len = (getTextBeforeCursor(1000, 0)?.length ?: 0) +
                            (getTextAfterCursor(1000, 0)?.length ?: 0)
                    // 【Fix】明文光标 → 掩码光标换算（掩码长度可能与明文长度不等）
                    val ratio = if (plainLen > 0) len.toFloat() / plainLen.toFloat() else 1f
                    val maskPos = kotlin.math.floor(cursor * ratio).toInt().coerceIn(0, len)
                    setSelection(maskPos, maskPos)
                    // 【Fix】删除长度按"掩码每字符代表多少明文"换算：
                    //  - 掩码比明文短（如整段只显示 1 个字符）：只有删完最后一个明文字符时才删 1 个掩码字符，
                    //    否则删 0 个，避免把整段密码一起删掉；
                    //  - 掩码比明文长（代理对等）：至少删 1 个。
                    val maskCount = if (len >= plainLen) {
                        // 掩码每字符对应 len/plainLen 个明文字符，至少 1 个
                        val per = (len.toFloat() / plainLen.toFloat()).toInt().coerceAtLeast(1)
                        per
                    } else {
                        // 掩码比明文短：只有明文删完（plainLen-1 == 0）才删 1 个掩码字符
                        if (plainLen - 1 <= 0) 1 else 0
                    }.coerceAtMost(maskPos)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        deleteSurroundingTextInCodePoints(maskCount, 0)
                    } else {
                        deleteSurroundingText(maskCount, 0)
                    }
                    // 【Fix】删除后重新读取掩码长度，再按新比例换算光标
                    val newLen = (getTextBeforeCursor(1000, 0)?.length ?: 0) +
                            (getTextAfterCursor(1000, 0)?.length ?: 0)
                    val newRatio = if (cachedPlaintextPassword.isNotEmpty())
                        newLen.toFloat() / cachedPlaintextPassword.length.toFloat() else 1f
                    val maskTarget = kotlin.math.floor(passwordCursorPosition * newRatio).toInt()
                        .coerceIn(0, maxOf(newLen, 0))
                    // 【Fix】先 predict 再 setSelection，理由同 commitTextInPassword
                    selection.predict(maskTarget)
                    setSelection(maskTarget, maskTarget)
                }
            } catch (e: Exception) {
                Timber.w("handlePasswordBackspaceKey: failed to delete: ${e.message}")
            }
        }
        // 3. 顶部框按明文缓存刷新
        inputView?.refreshPasswordTopEditText()
    }

    /**
     * 删除密码缓存中的一段文本（供 [deleteSelection] 使用）。
     *
     * 【注意】传入的 start/end 必须是**明文坐标**。密码框顶部框就是明文，
     * 因此直接取顶部框选区即可，不能用 [selection]（掩码坐标）。
     */
    private fun deletePasswordRange(start: Int, end: Int) {
        val safeStart = start.coerceIn(0, cachedPlaintextPassword.length)
        val safeEnd = end.coerceIn(safeStart, cachedPlaintextPassword.length)
        if (safeStart == safeEnd) return
        cachedPlaintextPassword = cachedPlaintextPassword.removeRange(safeStart, safeEnd)
        passwordCursorPosition = safeStart
        Timber.d("Password range deleted: [$safeStart,$safeEnd), cursor=$passwordCursorPosition")
    }

    fun handleReturnKey() {
        Timber.d("[IMS] handleReturnKey: inputType=${currentInputEditorInfo.inputType} imeOptions=${currentInputEditorInfo.imeOptions}")
        currentInputEditorInfo.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL) {
                Timber.d("[IMS] handleReturnKey: TYPE_NULL → sendEnter + hideSelf")
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                // 隐藏键盘
                requestHideSelf(0)
                return
            }
            if (imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
        Timber.d("[IMS] handleReturnKey: IME_FLAG_NO_ENTER_ACTION → commitNewline + hideSelf")
                commitText("\n")
                // 隐藏键盘
                requestHideSelf(0)
                return
            }
            if (actionLabel?.isNotEmpty() == true && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                Timber.d("[IMS] handleReturnKey: custom actionId=$actionId actionLabel=$actionLabel → performEditorAction + hideSelf")
                currentInputConnection.performEditorAction(actionId)
                // 隐藏键盘
                requestHideSelf(0)
                return
            }
            when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_NONE -> {
                    Timber.d("[IMS] handleReturnKey: action=UNSPECIFIED/NONE → commitNewline + hideSelf")
                    commitText("\n")
                    // 隐藏键盘
                    requestHideSelf(0)
                }
                else -> {
                    Timber.d("[IMS] handleReturnKey: action=$action → performEditorAction + hideSelf")
                    currentInputConnection.performEditorAction(action)
                    // 隐藏键盘
                    requestHideSelf(0)
                }
            }
        }
    }

    fun commitText(text: String, cursor: Int = -1) {
        val ic = currentInputConnection ?: return
        Timber.i("[IMS] commitText: text='$text' cursor=$cursor composingEmpty=${composing.isEmpty()}")
        // 【Fix】密码框走专用路径：
        // 顶部框是明文、目标框是掩码，两侧坐标不可映射，通用的
        // "selection.latest + setSelection(start) + commitText" 逻辑会把插入点算错
        // （selection.latest 来自掩码坐标，而明文缓存位置由 passwordCursorPosition 维护）。
        // 这里统一以明文缓存位置为插入点，写入后按明文缓存刷新顶部框。
        if (isPasswordInputType()) {
            commitTextInPassword(text)
            return
        }

        // when composing text equals commit content, finish composing text as-is
        if (composing.isNotEmpty() && composingText.toString() == text) {
            val c = if (cursor == -1) text.length else cursor
            val target = composing.start + c
            resetComposingState()
            ic.withBatchEdit {
                if (selection.current.start != target) {
                    selection.predict(target)
                    ic.setSelection(target, target)
                }
                ic.finishComposingText()
            }
            // 【Fix】先按顶部框光标对齐本端 selection，再投递顶部框内容同步，
            // 保证 syncToTopEditText 的 post 里对齐的是最新插入点。
            alignSelectionToTopEditText()
            // 同步到顶端EditText
            syncToTopEditText()
            return
        }
        // committed text should replace composing (if any), replace selected range (if any),
        // or simply prepend before cursor
        val start = if (composing.isEmpty()) selection.latest.start else composing.start
        resetComposingState()
        if (cursor == -1) {
            // 【Fix】不能只依赖目标输入框自身的 selection 做插入。
            // 顶部 EditText 移动光标后，本端 selection.latest 已更新到新位置，
            // 但目标 App 输入框的 selection 并不一定同步到位（部分输入框忽略 setSelection，
            // 或 onUpdateSelection 回调晚于下一次 commitText），
            // 于是第一次插入落在正确位置，之后目标框光标停在插入点+1，
            // 而 selection.latest 又被 syncToTopEditText 的 post 拉回，
            // 表现为"只有第一个字符插对，后续字符都追加到末尾"。
            // 这里显式把目标框 selection 定位到 start 再插入，保证插入点与顶部框一致。
            val target = start + text.length
            selection.predict(target)
            ic.withBatchEdit {
                // 只有当 start 落在目标框当前文本范围内时才定位，避免越界异常
                val len = (getTextBeforeCursor(1000, 0)?.length ?: 0) +
                        (getTextAfterCursor(1000, 0)?.length ?: 0)
                if (start in 0..len) {
                    setSelection(start, start)
                }
                commitText(text, 1)
                // 目标框可能把光标留在插入点之后，这里再对齐一次，避免后续插入漂移
                setSelection(target, target)
            }
        } else {
            val target = start + cursor
            selection.predict(target)
            ic.withBatchEdit {
                commitText(text, 1)
                setSelection(target, target)
            }
        }
        // 【Fix】插入后以顶部框光标为准，覆盖目标框可能上报的滞后光标。
        // 顺序必须在 syncToTopEditText 之前：alignSelection 内部会 post 一次对齐，
        // 与 syncToTopEditText 的 post 保持 FIFO，先刷新顶部框内容再对齐目标框。
        alignSelectionToTopEditText()
        // 同步到顶端EditText
        syncToTopEditText()
    }

    /**
     * 【Fix】密码框专用提交路径。
     *
     * 密码框的顶部框是明文、目标框是掩码，两侧长度/坐标不可映射，
     * 因此插入点只认明文缓存 [passwordCursorPosition]，不认 [selection]。
     *
     * 流程：
     *  1. 先按明文位置更新明文缓存（[insertTextInPassword]），得到新的明文与光标；
     *  2. 把目标框光标定位到同一位置（掩码坐标，需按掩码实际长度收敛），
     *     再 commitText 写入，最后把目标框光标放到插入点之后；
     *  3. 用明文缓存刷新顶部框内容与光标（[InputView.refreshPasswordTopEditText]）。
     */
    private fun commitTextInPassword(text: String) {
        val ic = currentInputConnection ?: return
        // 【Fix】必须记录插入前的明文长度与光标：insertTextInPassword 内部可能截断文本
        // （>100 字符）或在超 10000 时清空缓存，此时用 passwordCursorPosition - text.length 反推会错。
        val prevPlainLen = cachedPlaintextPassword.length
        val insertPos = passwordCursorPosition
        // 1. 更新明文缓存（内部会把 passwordCursorPosition 推进到插入点之后）
        insertTextInPassword(text)
        val target = passwordCursorPosition
        // 实际写入的明文长度（截断/清空后仍准确）：目标框只写这么多字符
        val insertedLen = (cachedPlaintextPassword.length - prevPlainLen).coerceAtLeast(0)
        val safeText = if (insertedLen in 1 until text.length) text.substring(0, insertedLen) else text
        resetComposingState()
        // 2. 目标框按掩码坐标写入
        try {
            ic.withBatchEdit {
                val len = (getTextBeforeCursor(1000, 0)?.length ?: 0) +
                        (getTextAfterCursor(1000, 0)?.length ?: 0)
                // 【Fix】明文插入点 → 掩码插入点换算：掩码长度可能与明文长度不等
                // （部分 App 用单个字符表示整段密码，或掩码是代理对），
                // 直接拿明文坐标 setSelection 会越界或被 App 收敛到末尾，导致插入位置错乱。
                val plainLen = prevPlainLen
                val ratio = if (plainLen > 0) len.toFloat() / plainLen.toFloat() else 1f
                // 【Fix】明文坐标 → 掩码坐标统一用 floor 并收敛，避免四舍五入越过插入点
                val maskPos = kotlin.math.floor(insertPos * ratio).toInt().coerceIn(0, len)
                setSelection(maskPos, maskPos)
                commitText(safeText, 1)
                // 目标框可能把光标留在别处，这里按掩码长度再收敛一次
                // 【Fix】新掩码长度 = 插入后重新读取（App 可能只写 1 个掩码字符，
                // 因此用实际长度更可靠；读不到时退回 旧长度 + 明文实际插入长度）
                val newLen = (getTextBeforeCursor(1000, 0)?.length ?: 0) +
                        (getTextAfterCursor(1000, 0)?.length ?: 0)
                // 明文光标 → 掩码光标换算，不能直接用 target（明文坐标）
                val newRatio = if (cachedPlaintextPassword.isNotEmpty())
                    newLen.toFloat() / cachedPlaintextPassword.length.toFloat() else 1f
                val maskTarget = kotlin.math.floor(target * newRatio).toInt()
                    .coerceIn(0, maxOf(newLen, 0))
                // 【Fix】必须先 predict 再 setSelection：predict 只登记"预期光标"，
                // 不改变 selection.latest；若在 setSelection 之后 predict，
                // 目标框的 onUpdateSelection 回调可能在 predict 之前就到达，
                // 从而被 CursorTracker 当成"用户输入"触发 fcitx focus 重置。
                selection.predict(maskTarget)
                setSelection(maskTarget, maskTarget)
            }
        } catch (e: Exception) {
            Timber.w("commitTextInPassword: failed to commit: ${e.message}")
        }
        Timber.d("commitTextInPassword: text='$text' insertPos=$insertPos cursor=$passwordCursorPosition")
        // 3. 顶部框按明文缓存刷新
        inputView?.refreshPasswordTopEditText()
    }

    private fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                ScancodeMapping.keyCodeToScancode(keyEventCode),
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    private fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                ScancodeMapping.keyCodeToScancode(keyEventCode),
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    fun deleteSelection() {
        // 【Fix】密码框走专用路径：selection 是掩码坐标，删除范围必须取顶部框明文选区。
        if (isPasswordInputType()) {
            deleteSelectionInPassword()
            return
        }
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return

        selection.predict(lastSelection.start)
        currentInputConnection?.commitText("", 1)

        // 【Fix】先对齐本端 selection，再投递顶部框内容同步（post FIFO 顺序）
        alignSelectionToTopEditText()
        // 删除操作后同步到顶端EditText
        syncToTopEditText()
    }

    /**
     * 【Fix】密码框专用选区删除。
     *
     * 删除范围取顶部框明文选区（顶部框就是明文），目标框按同一位置（掩码坐标收敛）删除，
     * 最后按明文缓存刷新顶部框。
     */
    private fun deleteSelectionInPassword() {
        val view = inputView
        val (start, end) = view?.getTopEditTextSelection() ?: (0 to 0)
        if (start == end) return
        // 【Fix】目标框里是掩码文本，长度与明文缓存不一定相等（掩码替换 / Unicode / 部分 App
        // 用单个字符表示整段密码），因此不能把明文选区 [start,end) 直接当掩码坐标用。
        // 这里按"明文长度 → 掩码长度"的比例换算删除范围，保证删掉的字符数与明文一致。
        val plainLen = cachedPlaintextPassword.length
        val ic = currentInputConnection
        val maskLen = if (ic != null) {
            (ic.getTextBeforeCursor(1000, 0)?.length ?: 0) +
                    (ic.getTextAfterCursor(1000, 0)?.length ?: 0)
        } else {
            plainLen
        }
        val maskStart: Int
        val maskEnd: Int
        if (plainLen <= 0 || maskLen == plainLen) {
            maskStart = start.coerceIn(0, maskLen)
            maskEnd = end.coerceIn(maskStart, maskLen)
        } else {
            val ratio = maskLen.toFloat() / plainLen.toFloat()
            maskStart = kotlin.math.floor(start * ratio).toInt().coerceIn(0, maskLen)
            // 【Fix】end 用 ceil：否则掩码比明文短时会漏删尾部字符
            maskEnd = kotlin.math.ceil(end * ratio).toInt().coerceIn(maskStart, maskLen)
        }
        // 1. 更新明文缓存（明文坐标）
        deletePasswordRange(start, end)
        val cursor = passwordCursorPosition
        // 2. 目标框按换算后的掩码坐标删除
        if (ic != null) {
            try {
                ic.withBatchEdit {
                    setSelection(maskStart, maskEnd)
                    commitText("", 1)
                    // 【Fix】删除后重新读取掩码长度，再按新比例换算光标
                    val newLen = (getTextBeforeCursor(1000, 0)?.length ?: 0) +
                            (getTextAfterCursor(1000, 0)?.length ?: 0)
                    val newRatio = if (cachedPlaintextPassword.isNotEmpty())
                        newLen.toFloat() / cachedPlaintextPassword.length.toFloat() else 1f
                    val maskTarget = kotlin.math.floor(cursor * newRatio).toInt()
                        .coerceIn(0, maxOf(newLen, 0))
                    // 【Fix】先 predict 再 setSelection，理由同 commitTextInPassword
                    selection.predict(maskTarget)
                    setSelection(maskTarget, maskTarget)
                }
            } catch (e: Exception) {
                Timber.w("deleteSelectionInPassword: failed to delete: ${e.message}")
            }
        }
        Timber.d("deleteSelectionInPassword: plain=[$start,$end) mask=[$maskStart,$maskEnd) cursor=$cursor")
        // 3. 顶部框按明文缓存刷新
        view?.refreshPasswordTopEditText()
    }

    fun sendCombinationKeyEvents(
        keyEventCode: Int,
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false
    ) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun applySelectionOffset(offsetStart: Int, offsetEnd: Int = 0) {
        // 【Fix】密码框：selection 里是掩码坐标，不能拿来做偏移基准，
        // 否则方向键移动会按掩码长度漂移。这里以明文缓存光标为基准，
        // 只更新明文缓存并刷新顶部框，目标框光标由下一次写入时再定位。
        if (isPasswordInputType()) {
            val plainLen = cachedPlaintextPassword.length
            passwordCursorPosition = (passwordCursorPosition + offsetStart).coerceIn(0, plainLen)
            // 【Fix】同时把目标框光标按比例挪到对应掩码位置：
            // 否则用户移动光标后，若目标 App 在 commitText 时忽略 setSelection，
            // 插入点会仍停在旧位置（顶部框光标动了、实际插入位置不变）。
            val ic = currentInputConnection
            if (ic != null) {
                try {
                    ic.withBatchEdit {
                        val maskLen = (getTextBeforeCursor(1000, 0)?.length ?: 0) +
                                (getTextAfterCursor(1000, 0)?.length ?: 0)
                        val ratio = if (plainLen > 0) maskLen.toFloat() / plainLen.toFloat() else 1f
                        val maskPos = kotlin.math.floor(passwordCursorPosition * ratio).toInt()
                            .coerceIn(0, maskLen)
                        selection.predict(maskPos)
                        setSelection(maskPos, maskPos)
                    }
                } catch (e: Exception) {
                    Timber.w("applySelectionOffset(password): setSelection failed: ${e.message}")
                }
            }
            inputView?.refreshPasswordTopEditText()
            return
        }
        val lastSelection = selection.latest
        currentInputConnection?.also {
            val start = max(lastSelection.start + offsetStart, 0)
            val end = max(lastSelection.end + offsetEnd, 0)
            if (start > end) return
            selection.predict(start, end)
            it.setSelection(start, end)
            // 【Fix】目标框光标由本端显式移动；顶部框随后会跟随（见 syncToTopEditText），
            // 但若目标框忽略 setSelection，顶部框会被拉回旧光标。因此这里置 pending：
            // 目标框接受则升级为权威，不接受则跟随目标框，避免两侧长期不一致。
            inputView?.markTopCursorPending()
            inputView?.markTopCursorSyncIssued()
            syncToTopEditText()
        }
    }

    fun cancelSelection() {
        // 【Fix】密码框：selection 是掩码坐标，取消选区只需把顶部框恢复成明文光标，
        // 不能把掩码坐标写进明文缓存。
        if (isPasswordInputType()) {
            inputView?.refreshPasswordTopEditText()
            return
        }
        val lastSelection = selection.latest
        if (lastSelection.isEmpty()) return
        val end = lastSelection.end
        selection.predict(end)
        currentInputConnection?.setSelection(end, end)
        // 【Fix】同 applySelectionOffset：置 pending + 已发出，由目标框回调裁决
        inputView?.markTopCursorPending()
        inputView?.markTopCursorSyncIssued()
        syncToTopEditText()
    }

    /**
     * 获取目标输入框的完整文本内容
     * 对于密码输入框，返回明文而不是掩码
     */
    fun getTargetInputFieldContent(): String {
        val ic = currentInputConnection ?: return ""
        return try {
            // 检测是否为密码输入框
            val isPassword = isPasswordInputType()
            
            if (isPassword) {
                // 对于密码输入框，我们需要维护明文密码
                // 由于无法直接获取密码明文，我们通过跟踪输入来维护
                return getPlaintextPassword()
            }
            
            // 使用更保守的最大长度限制，避免内存溢出
            val maxLength = 1000 // 进一步限制为1000字符，避免内存问题
            
            // 获取光标前的文本，添加额外的长度检查
            val beforeCursor = try {
                val text = ic.getTextBeforeCursor(maxLength, 0)?.toString() ?: ""
                if (text.length > maxLength) {
                    text.substring(0, maxLength)
                } else {
                    text
                }
            } catch (e: Exception) {
                Timber.w("Failed to get text before cursor: ${e.message}")
                ""
            }
            
            // 获取光标后的文本，添加额外的长度检查
            val afterCursor = try {
                val text = ic.getTextAfterCursor(maxLength, 0)?.toString() ?: ""
                if (text.length > maxLength) {
                    text.substring(0, maxLength)
                } else {
                    text
                }
            } catch (e: Exception) {
                Timber.w("Failed to get text after cursor: ${e.message}")
                ""
            }
            
            // 获取当前选中的文本，添加长度检查
            val selectedText = try {
                val text = ic.getSelectedText(0)?.toString() ?: ""
                if (text.length > maxLength) {
                    text.substring(0, maxLength)
                } else {
                    text
                }
            } catch (e: Exception) {
                Timber.w("Failed to get selected text: ${e.message}")
                ""
            }
            
            // 组合文本，并确保总长度不超过限制
            val combinedText = if (selectedText.isNotEmpty()) {
                beforeCursor + selectedText + afterCursor
            } else {
                beforeCursor + afterCursor
            }
            
            // 最终长度检查，确保不会导致内存问题
            if (combinedText.length > maxLength * 2) {
                combinedText.substring(0, maxLength * 2)
            } else {
                combinedText
            }
        } catch (e: Exception) {
            Timber.w("Failed to get target input field content: ${e.message}")
            ""
        }
    }

    /**
     * 获取目标输入框中光标的位置
     */
    fun getTargetInputFieldCursorPosition(): Int {
        val ic = currentInputConnection ?: return 0
        // 【Fix】密码框的掩码文本长度与明文缓存长度可能不一致（掩码替换、Unicode 等），
        // 直接返回明文缓存中的光标位置，避免顶端 EditText 光标错位。
        if (isPasswordInputType()) return passwordCursorPosition
        return try {
            // 使用更保守的最大长度限制
            val maxLength = 1000
            val beforeCursor = try {
                val text = ic.getTextBeforeCursor(maxLength, 0)?.toString() ?: ""
                if (text.length > maxLength) {
                    text.substring(0, maxLength)
                } else {
                    text
                }
            } catch (e: Exception) {
                Timber.w("Failed to get text before cursor for position: ${e.message}")
                ""
            }
            beforeCursor.length
        } catch (e: Exception) {
            Timber.w("Failed to get target input field cursor position: ${e.message}")
            0
        }
    }

    /**
     * 同步目标输入框内容到顶端EditText
     * 【Fix】改为 post 异步执行，避免在 onMeasure / commitText 等主线程路径上
     * 同步触发 getTextBeforeCursor + getTextAfterCursor + getSelectedText 三次 Binder IPC，
     * 消除首次展示时 InputView onMeasure 卡顿 900ms + 503ms。
     * 【Fix2】加 pendingSyncToTopEditText 标志做 debounce：连续多次调用只投递一次任务到
     * 主线程队列，避免中文快速输入时队列里堆积大量 syncFromTargetInputField 任务，
     * 与 preedit 更新竞争主线程时间片导致预展示延迟。
     */
    private var pendingSyncToTopEditText = false

    private fun syncToTopEditText() {
        val view = inputView ?: return
        if (pendingSyncToTopEditText) return
        pendingSyncToTopEditText = true
        view.post {
            pendingSyncToTopEditText = false
            try {
                if (isPasswordInputType()) {
                    checkPasswordCacheSize()
                }
                view.syncFromTargetInputField()
            } catch (e: Exception) {
                Timber.w("Failed to sync to top EditText: ${e.message}")
            }
            // 【Fix】顶部框内容已刷新，此时再以顶部框光标对齐一次目标框，
            // 保证下一次插入点与用户看到的插入符一致。
            // 注意：pending（顶部框刚移动光标、目标框尚未确认）时也要对齐，
            // 否则目标框忽略 setSelection 的场景下插入点会一直停在旧位置。
            val v = inputView
            // 【Fix】密码框不走权威/待确认状态机（两侧坐标不可映射），
            // 目标框光标已由各写入路径自行定位，这里不再对齐，避免用掩码坐标覆盖明文缓存。
            if (v != null && !isPasswordInputType() &&
                (v.isTopEditTextAuthoritative() || v.isTopCursorPending())
            ) {
                alignSelectionToTopEditTextNow()
            }
        }
    }

    /**
     * 检测当前输入框是否为密码输入框
     */
    fun isPasswordInputType(): Boolean {
        val inputType = currentInputEditorInfo.inputType
        // 【Fix】TYPE_TEXT_VARIATION_PASSWORD 等变体位只在 CLASS 为 TYPE_CLASS_TEXT 时才有效。
        // 若不做 CLASS 判断，形如 inputType=163969（CLASS_NUMBER + 0x80 位）的普通数字框
        // 会被误判为密码框，从而走掩码缓存路径，导致光标/内容同步异常。
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) {
            return false
        }
        return (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
               (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
               (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
               (inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    // 密码管理相关变量和方法
    private var cachedPlaintextPassword = ""
    private var passwordCursorPosition = 0
    
    // 密码长度监控
    private var lastPasswordLengthCheck = 0L
    private val PASSWORD_LENGTH_CHECK_INTERVAL = 5000L // 5秒检查一次
    
    /**
     * 检查密码缓存长度，防止异常增长
     */
    private fun checkPasswordCacheSize() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPasswordLengthCheck < PASSWORD_LENGTH_CHECK_INTERVAL) {
            return
        }
        lastPasswordLengthCheck = currentTime
        
        if (cachedPlaintextPassword.length > 10000) {
            Timber.w("Password cache size exceeded limit (${cachedPlaintextPassword.length}), clearing cache")
            clearPasswordCache()
        }
    }

    /**
     * 获取密码框明文缓存中的光标位置（供 InputView 同步顶端 EditText 光标使用）
     */
    fun getPasswordCursorPosition(): Int = passwordCursorPosition

    /**
     * 设置密码框明文缓存中的光标位置（供 InputView 在顶端 EditText 移动光标时调用）
     */
    fun setPasswordCursorPosition(position: Int) {
        val safe = position.coerceIn(0, cachedPlaintextPassword.length)
        if (passwordCursorPosition != safe) {
            passwordCursorPosition = safe
            Timber.d("Password cursor set to $safe")
        }
    }

    /**
     * 获取明文密码
     */
    private fun getPlaintextPassword(): String {
        // 定期检查密码缓存大小
        checkPasswordCacheSize()
        return cachedPlaintextPassword
    }

    // 【Fix】原 updatePlaintextPassword() 已删除：无任何调用点，
    // 且它会无条件把光标推到末尾，与"明文缓存 + 光标"分离维护的模型冲突。

    /**
     * 在密码中插入文本
     */
    private fun insertTextInPassword(text: String) {
        // 防止OOM：限制输入文本长度
        val safeText = if (text.length > 100) {
            Timber.w("Input text too long (${text.length}), truncating to 100 chars")
            text.substring(0, 100)
        } else {
            text
        }
        
        // 防止OOM：检查结果长度
        val newLength = cachedPlaintextPassword.length + safeText.length
        if (newLength > 10000) {
            Timber.w("Password would exceed limit after insertion ($newLength), clearing cache")
            clearPasswordCache()
            cachedPlaintextPassword = safeText
            passwordCursorPosition = safeText.length
            return
        }
        
        // 使用StringBuilder避免多次字符串拼接
        val sb = StringBuilder(cachedPlaintextPassword.length + safeText.length)
        if (passwordCursorPosition > 0) {
            sb.append(cachedPlaintextPassword.substring(0, passwordCursorPosition))
        }
        sb.append(safeText)
        if (passwordCursorPosition < cachedPlaintextPassword.length) {
            sb.append(cachedPlaintextPassword.substring(passwordCursorPosition))
        }
        
        cachedPlaintextPassword = sb.toString()
        passwordCursorPosition += safeText.length
    }

    /**
     * 从密码中删除文本
     */
    private fun deleteTextFromPassword(before: Int, after: Int) {
        val start = maxOf(0, passwordCursorPosition - before)
        val end = minOf(cachedPlaintextPassword.length, passwordCursorPosition + after)
        
        val beforeDelete = cachedPlaintextPassword.substring(0, start)
        val afterDelete = cachedPlaintextPassword.substring(end)
        
        cachedPlaintextPassword = beforeDelete + afterDelete
        passwordCursorPosition = start
    }

    /**
     * 清空密码缓存（公共方法，供 InputView 调用）
     */
    fun clearPasswordCache() {
        cachedPlaintextPassword = ""
        passwordCursorPosition = 0
        Timber.d("Password cache cleared")
    }

    /**
     * 从现有密码输入框内容初始化密码缓存
     * 对于已有内容的密码输入框，我们无法获取明文，但可以根据掩码长度推断密码长度
     */
    private fun initializePasswordCacheFromExistingContent() {
        val ic = currentInputConnection ?: return
        try {
            // 获取密码输入框的掩码内容
            val maxLength = 1000 // 合理的最大长度
            val beforeCursor = ic.getTextBeforeCursor(maxLength, 0)?.toString() ?: ""
            val afterCursor = ic.getTextAfterCursor(maxLength, 0)?.toString() ?: ""
            val selectedText = ic.getSelectedText(0)?.toString() ?: ""
            
            // 计算总长度
            val totalLength = beforeCursor.length + afterCursor.length + selectedText.length
            val cursorPos = beforeCursor.length
            
            if (totalLength > 0) {
                // 创建占位符明文，用户后续输入时会逐步替换
                cachedPlaintextPassword = "•".repeat(totalLength)
                passwordCursorPosition = cursorPos
                
                Timber.d("Initialized password cache with $totalLength placeholder characters, cursor at $cursorPos")
            }
        } catch (e: Exception) {
            Timber.w("Failed to initialize password cache from existing content: ${e.message}")
            // 如果出错，保持空缓存
            clearPasswordCache()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        postFcitxJob { reset() }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        try {
            highlightColor = styledColor(android.R.attr.colorAccent).alpha(0.4f)
        } catch (_: Exception) {
            Timber.w("Device does not support android.R.attr.colorAccent which it should have.")
        }
        InputFeedbacks.syncSystemPrefs()
        // navbar foreground/background color would reset every time window shows
        navbarMgr.update(window.window!!)
    }

    override fun onCreateInputView(): View? {
        // 【Fix】如果 onCreate 的预热已经创建好了 InputView，直接复用，
        // 避免重复构造 TextKeyboard（40 个 KeyView + 大量 drawable → GC → onMeasure 卡顿）。
        if (inputView == null) {
            Timber.d("[IMS] onCreateInputView: pre-warm missed, creating InputView now")
            replaceInputViews(ThemeManager.activeTheme)
        } else {
            Timber.d("[IMS] onCreateInputView: reusing pre-warmed InputView")
            // 【Fix】预热时 setInputView 已将 inputView 加入 mInputFrame，
            // onCreateInputView 再次复用时 super.setInputView 内部会再次 addView，
            // 导致 "child already has a parent" crash。先 removeView 解除旧父容器关联。
            (inputView!!.parent as? ViewGroup)?.removeView(inputView)
            setInputView(inputView!!)
        }
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        // input method layout has not changed in 11 years:
        // https://android.googlesource.com/platform/frameworks/base/+/ae3349e1c34f7aceddc526cd11d9ac44951e97b6/core/res/res/layout/input_method.xml
        // expand inputArea to fullscreen
        contentView.findViewById<FrameLayout>(android.R.id.inputArea)
            .updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        /**
         * expand InputView to fullscreen, since [android.inputmethodservice.InputMethodService.setInputView]
         * would set InputView's height to [ViewGroup.LayoutParams.WRAP_CONTENT]
         */
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private var inputViewLocation = intArrayOf(0, 0)

    override fun onComputeInsets(outInsets: Insets) {
        if (inputDeviceMgr.isVirtualKeyboard) {
            inputView?.keyboardView?.getLocationInWindow(inputViewLocation)
            outInsets.apply {
                contentTopInsets = inputViewLocation[1]
                visibleTopInsets = inputViewLocation[1]
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        } else {
            val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onEvaluateFullscreenMode() = false

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        // reason to use a self increment index rather than timestamp:
        // KeyUp and KeyDown events actually can happen on the same time
        val timestamp = cachedKeyEventIndex++
        cachedKeyEvents.put(timestamp, event)
        val up = event.action == KeyEvent.ACTION_UP
        val states = KeyStates.fromKeyEvent(event)
        val charCode = event.unicodeChar
        Timber.d("[IMS] forwardKeyEvent: keyCode=${event.keyCode} charCode=$charCode up=$up ts=$timestamp")
        // try send charCode first, allow upper case and lower case character generating different KeySym
        // skip \t, because it's charCode is different from KeySym
        // skip \n, because fcitx wants \r for return
        // skip ' ', because it would produce same KeySym regardless of the modifier
        if (charCode > 0 && charCode != '\t'.code && charCode != '\n'.code && charCode != ' '.code) {
            // drop modifier state when using combination keys to input number/symbol on some phones
            // because fcitx doesn't recognize selection key with modifiers (eg. Alt+Q for 1)
            // in which case event.getNumber().toInt() == event.getUnicodeChar()
            val s = if (event.number.code == charCode) KeyStates.Empty else states
            Timber.d("[IMS] forwardKeyEvent: send charCode=$charCode states=$s")
            postFcitxJob {
                sendKey(charCode, s.states, event.scanCode, up, timestamp)
            }
            return true
        }
        val keySym = KeySym.fromKeyEvent(event)
        if (keySym != null) {
            Timber.d("[IMS] forwardKeyEvent: send keySym=$keySym states=$states")
            postFcitxJob {
                sendKey(keySym, states, event.scanCode, up, timestamp)
            }
            return true
        }
        Timber.d("[IMS] forwardKeyEvent: Skipped KeyEvent: $event")
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Timber.d("[IMS] onKeyDown: keyCode=$keyCode")
        // request to show floating CandidatesView when pressing physical keyboard
        if (inputDeviceMgr.evaluateOnKeyDown(event, this)) {
            Timber.d("[IMS] onKeyDown: physical keyboard detected, focus + forceShow")
            postFcitxJob {
                focus(true)
            }
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        Timber.d("[IMS] onKeyUp: keyCode=$keyCode")
        return forwardKeyEvent(event) || super.onKeyUp(keyCode, event)
    }

    // Added in API level 14, deprecated in 29
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        if (Build.VERSION.SDK_INT < 34) {
            inputDeviceMgr.evaluateOnViewClicked(this)
        }
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceMgr.evaluateOnUpdateEditorToolType(toolType, this)
    }

    private var firstBindInput = true

    override fun onBindInput() {
        val uid = currentInputBinding.uid
        val pkgName = pkgNameCache.forUid(uid)
        Timber.i("[IMS] onBindInput: uid=$uid pkg=$pkgName firstBindInput=$firstBindInput")
        postFcitxJob {
            // ensure InputContext has been created before focusing it
            Timber.d("[IMS] onBindInput: activating InputContext uid=$uid pkg=$pkgName")
            activate(uid, pkgName)
        }
        if (firstBindInput) {
            firstBindInput = false
            // only use input method from subtype for the first `onBindInput`, because
            // 1. fcitx has `ShareInputState` option, thus reading input method from subtype
            //    everytime would ruin `ShareInputState=Program`
            // 2. im from subtype should be read once, when user changes input method from other
            //    app to a subtype of ours via system input method picker (on 34+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val subtype = inputMethodManager.currentInputMethodSubtype ?: return
                val im = SubtypeManager.inputMethodOf(subtype)
                Timber.d("[IMS] onBindInput: firstBind API34+, activateIme im=$im")
                postFcitxJob {
                    activateIme(im)
                }
            }
        }
    }

    /**
     * When input method changes internally (eg. via language switch key or keyboard shortcut),
     * we want to notify system that subtype has changed (see [^1]), then ignore the incoming
     * [onCurrentInputMethodSubtypeChanged] callback.
     * Input method should only be changed when user changes subtype in system input method picker
     * manually.
     */
    private var skipNextSubtypeChange: String? = null

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val im = SubtypeManager.inputMethodOf(newSubtype)
            Timber.d("onCurrentInputMethodSubtypeChanged: im=$im")
            // don't change input method if this "subtype change" was our notify to system
            // see [^1]
            if (skipNextSubtypeChange == im) {
                skipNextSubtypeChange = null
                return
            }
            postFcitxJob {
                activateIme(im)
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        // update selection as soon as possible
        // sometimes when restarting input, onUpdateSelection happens before onStartInput, and
        // initialSel{Start,End} is outdated. but it's the client app's responsibility to send
        // right cursor position, try to workaround this would simply introduce more bugs.
        selection.resetTo(attribute.initialSelStart, attribute.initialSelEnd)
        resetComposingState()
        
        // 清空密码缓存，准备处理新的输入框
        clearPasswordCache()
        
        // 重置密码输入框状态，准备处理新的输入框
        inputView?.resetPasswordFieldState()
        
        // 如果是密码输入框且已有内容，初始化密码缓存
        if (isPasswordInputType()) {
            initializePasswordCacheFromExistingContent()
            Timber.d("onStartInput: password cache len=${cachedPlaintextPassword.length} cursor=$passwordCursorPosition")
        }
        
        val flags = CapabilityFlags.fromEditorInfo(attribute)
        capabilityFlags = flags
        // 【Fix】新的输入会话开始：撤销顶部框的光标权威状态，
        // 后续以目标 App 输入框上报的内容/光标为准。
        inputView?.clearTopEditTextAuthoritative()
        Timber.d("onStartInput: initialSel=${selection.current}, restarting=$restarting")
        // wait until InputContext created/activated
        postFcitxJob {
            if (restarting) {
                // when input restarts in the same editor, focus out to clear previous state
                focus(false)
                // try focus out before changing CapabilityFlags,
                // to avoid confusing state of different text fields
            }
            // EditorInfo can be different in onStartInput and onStartInputView,
            // especially in browsers
            setCapFlags(flags)
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        Timber.d("onStartInputView: restarting=$restarting")
        postFcitxJob {
            focus(true)
        }
        if (inputDeviceMgr.evaluateOnStartInputView(info, this)) {
            // because onStartInputView will always be called after onStartInput,
            // editorInfo and capFlags should be up-to-date
            inputView?.startInput(info, capabilityFlags, restarting)
        } else {
            if (currentInputConnection?.monitorCursorAnchor() != true) {
                if (!decorLocationUpdated) {
                    updateDecorLocation()
                }
                // anchor CandidatesView to bottom-left corner in case InputConnection does not
                // support monitoring CursorAnchorInfo
                workaroundNullCursorAnchorInfo()
            }
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        // onUpdateSelection can left behind when user types quickly enough, eg. long press backspace
        cursorUpdateIndex += 1
        Timber.d("onUpdateSelection: old=[$oldSelStart,$oldSelEnd] new=[$newSelStart,$newSelEnd]")
        handleCursorUpdate(newSelStart, newSelEnd, cursorUpdateIndex)
        // 【Fix】顶部框光标"待确认"状态在这里裁决：
        // 目标框上报的光标与顶部框一致 → 确认顶部框权威（后续滞后回调不再覆盖）；
        // 不一致 → 说明目标框没接受顶部框的 setSelection，丢弃 pending，跟随目标框。
        // 必须在 updateSelection 之前处理，保证同一回调内状态一致。
        // 组字期间（composing 非空）顶部框内容不跟随目标框，两侧坐标无意义，跳过裁决。
        // 【Fix】"拒绝"只对顶部框主动发起的 setSelection 生效（pending 且未标记为已发出）：
        // 若顶部框刚把光标同步给目标框，目标框紧接着回调一个旧光标，会立刻把 pending 拒掉，
        // 导致后续滞后回调继续覆盖顶部框（表现为光标跳变）。因此顶部框发出的 setSelection
        // 先标记为"已发出"，其后的第一个回调视为回声（一致则确认，不一致则忽略），
        // 只有再往后的回调才允许拒绝。
        val view = inputView
        if (view != null && !isPasswordInputType() && !isComposing) {
            val (topStart, topEnd) = view.getTopEditTextSelection()
            if (topStart == newSelStart && topEnd == newSelEnd) {
                view.confirmTopEditTextAuthoritative()
            } else if (view.isTopCursorSyncIssued()) {
                view.markTopCursorSyncEchoConsumed()
                Timber.v("onUpdateSelection: ignore echo ($newSelStart,$newSelEnd), top=($topStart,$topEnd)")
            } else {
                view.rejectTopCursorPending()
            }
        }
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    private fun updateDecorLocation() {
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] = contentView.height.toFloat()
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    private val anchorPosition = floatArrayOf(0f, 0f, 0f, 0f)

    /**
     * anchor candidates view to bottom-left corner, only works if [decorLocationUpdated]
     */
    private fun workaroundNullCursorAnchorInfo() {
        anchorPosition[0] = 0f
        anchorPosition[1] = contentSize[1]
        anchorPosition[2] = 0f
        anchorPosition[3] = contentSize[1]
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val bounds = info.getCharacterBounds(0)
        if (bounds != null) {
            // anchor to start of composing span instead of insertion mark if available
            val horizontal =
                if (candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            anchorPosition[0] = horizontal
            anchorPosition[1] = bounds.bottom
            anchorPosition[2] = horizontal
            anchorPosition[3] = bounds.top
        } else {
            anchorPosition[0] = info.insertionMarkerHorizontal
            anchorPosition[1] = info.insertionMarkerBottom
            anchorPosition[2] = info.insertionMarkerHorizontal
            anchorPosition[3] = info.insertionMarkerTop
        }
        // avoid calling `decorView.getLocationOnScreen` repeatedly
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            // anchor candidates view to bottom-left corner in case CursorAnchorInfo is invalid
            workaroundNullCursorAnchorInfo()
            return
        }
        // params of `Matrix.mapPoints` must be [x0, y0, x1, y1]
        info.matrix.mapPoints(anchorPosition)
        val (xOffset, yOffset) = decorLocation
        anchorPosition[0] -= xOffset
        anchorPosition[1] -= yOffset
        anchorPosition[2] -= xOffset
        anchorPosition[3] -= yOffset
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    private fun handleCursorUpdate(newSelStart: Int, newSelEnd: Int, updateIndex: Int) {
        val predicted = selection.consume(newSelStart, newSelEnd)
        if (!predicted) {
            // cursor update can't match any prediction: it's treated as a user input
            selection.resetTo(newSelStart, newSelEnd)
        }
        // 【Fix】密码框光标位置必须无条件跟随，无论这次 onUpdateSelection 是否命中预测。
        // 原实现把 passwordCursorPosition 的更新放在 `else`（未命中预测）分支里，
        // 而顶端 EditText 移动光标时 syncCursorToTargetInputField() 会先 predictSelection()，
        // 于是回调必然命中预测 → 提前 return → passwordCursorPosition 永远不更新，
        // 后续 insertTextInPassword() 仍按旧位置插入，表现为"顶部框光标动了、插入位置不变"。
        // 注意：目标框上报的是掩码坐标，长度可能大于明文缓存，这里用 coerce 兜底。
        // 【Fix2】但本端主动写入（commitText / 退格 / 删除 / 顶部框移动光标）之后，
        // 目标框会回调一个由掩码文本算出的坐标，此时明文缓存才是真相，
        // 若仍无条件覆盖，会把刚设定好的明文光标拉偏（表现为"移动光标后插入位置不对"）。
        // 因此只在本端未主动发起的回调（!predicted）上跟随目标框。
        if (isPasswordInputType() && !predicted && newSelStart == newSelEnd && newSelStart >= 0) {
            val safe = newSelStart.coerceAtMost(cachedPlaintextPassword.length)
            if (passwordCursorPosition != safe) {
                passwordCursorPosition = safe
                Timber.d("Password cursor position updated: $passwordCursorPosition")
            }
        }
        // skip selection range update, we only care about selection cursor (zero width) here
        if (newSelStart != newSelEnd) return
        // do reset if composing is empty && input panel is not empty
        if (composing.isEmpty()) {
            postFcitxJob {
                if (!isEmpty()) {
                    Timber.d("handleCursorUpdate: reset")
                    reset()
                }
            }
            return
        }
        // check if cursor inside composing text
        if (composing.contains(newSelStart)) {
            if (ignoreSystemCursor) return
            // fcitx cursor position is relative to client preedit (composing text)
            val position = newSelStart - composing.start
            // move fcitx cursor when cursor position changed
            if (position != composingText.cursor) {
                // cursor in InvokeActionEvent counts by "UTF-8 characters"
                val codePointPosition = composingText.codePointCountUntil(position)
                postFcitxJob {
                    if (updateIndex != cursorUpdateIndex) return@postFcitxJob
                    Timber.d("handleCursorUpdate: move fcitx cursor to $codePointPosition")
                    moveCursor(codePointPosition)
                }
            }
        } else {
            Timber.d("handleCursorUpdate: focus out/in")
            resetComposingState()
            // cursor outside composing range, finish composing as-is
            currentInputConnection?.finishComposingText()
            // `fcitx.reset()` here would commit preedit after new cursor position
            // since we have `ClientUnfocusCommit`, focus out and in would do the trick
            postFcitxJob {
                focus(false)
                focus(true)
            }
        }
    }

    // because setComposingText(text, cursor) can only put cursor at end of composing,
    // sometimes onUpdateSelection would receive event with wrong cursor position.
    // those events need to be filtered.
    // because of https://android.googlesource.com/platform/frameworks/base.git/+/refs/tags/android-11.0.0_r45/core/java/android/view/inputmethod/BaseInputConnection.java#851
    // it's not possible to set cursor inside composing text
    private fun updateComposingText(text: FormattedText) {
        val ic = currentInputConnection ?: return
        val lastSelection = selection.latest
        ic.beginBatchEdit()
        if (composingText.spanEquals(text)) {
            // composing text content is up-to-date
            // update cursor only when it's not empty AND cursor position is valid
            if (text.length > 0 && text.cursor >= 0) {
                val p = text.cursor + composing.start
                if (p != lastSelection.start) {
                    Timber.d("updateComposingText: set Android selection ($p, $p)")
                    ic.setSelection(p, p)
                    selection.predict(p)
                }
            }
        } else {
            // composing text content changed
            Timber.d("updateComposingText: '$text' lastSelection=$lastSelection")
            if (text.isEmpty()) {
                if (composing.isEmpty()) {
                    // do not reset saved selection range when incoming composing
                    // and saved composing range are both empty:
                    // composing.start is invalid when it's empty.
                    selection.predict(lastSelection.start)
                } else {
                    // clear composing text, put cursor at start of original composing
                    selection.predict(composing.start)
                    composing.clear()
                }
                ic.setComposingText("", 1)
            } else {
                val start = if (composing.isEmpty()) lastSelection.start else composing.start
                composing.update(start, start + text.length)
                // skip cursor reposition when:
                // - preedit cursor is at the end
                // - cursor position is invalid
                if (text.cursor == text.length || text.cursor < 0) {
                    selection.predict(composing.end)
                    ic.setComposingText(text.toSpannedString(highlightColor), 1)
                } else {
                    val p = text.cursor + composing.start
                    selection.predict(p)
                    ic.setComposingText(text.toSpannedString(highlightColor), 1)
                    ic.setSelection(p, p)
                }
            }
            Timber.d("updateComposingText: composing=$composing")
        }
        composingText = text
        ic.endBatchEdit()
    }

    /**
     * Finish composing text and leave cursor position as-is.
     * Also updates internal composing state of [FcitxInputMethodService].
     */
    fun finishComposing() {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty()) return
        composing.clear()
        composingText = FormattedText.Empty
        ic.finishComposingText()
    }

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        // ignore inline suggestion when disabled by user || using physical keyboard with floating candidates view
        if (!inlineSuggestions || !inputDeviceMgr.isVirtualKeyboard) return null
        val theme = ThemeManager.activeTheme
        val chipDrawable =
            if (theme.isDark) R.drawable.bkg_inline_suggestion_dark else R.drawable.bkg_inline_suggestion_light
        val chipBg = Icon.createWithResource(this, chipDrawable).setTint(theme.keyTextColor)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(
                ViewStyle.Builder()
                    .setBackgroundColor(Color.TRANSPARENT)
                    .setPadding(0, 0, 0, 0)
                    .build()
            )
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(chipBg)
                    .setPadding(dp(10), 0, dp(10), 0)
                    .build()
            )
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setLayoutMargin(dp(4), 0, dp(4), 0)
                    .setTextColor(theme.keyTextColor)
                    .setTextSize(14f)
                    .build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(theme.altKeyTextColor)
                    .setTextSize(12f)
                    .build()
            )
            .setStartIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .setEndIconStyle(
                ImageViewStyle.Builder()
                    .setTintList(ColorStateList.valueOf(theme.altKeyTextColor))
                    .build()
            )
            .build()
        val styleBundle = UiVersions.newStylesBuilder()
            .addStyle(style)
            .build()
        val spec = InlinePresentationSpec
            .Builder(Size(0, 0), Size(Int.MAX_VALUE, Int.MAX_VALUE))
            .setStyle(styleBundle)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inlineSuggestions || !inputDeviceMgr.isVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        decorLocationUpdated = false
        inputDeviceMgr.onFinishInputView()
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        resetComposingState()
        postFcitxJob {
            focus(false)
        }
        showingDialog?.dismiss()
    }

    override fun onFinishInput() {
        Timber.d("onFinishInput")
        capabilityFlags = CapabilityFlags.DefaultFlags
    }

    override fun onUnbindInput() {
        cachedKeyEvents.evictAll()
        cachedKeyEventIndex = 0
        cursorUpdateIndex = 0
        // currentInputBinding can be null on some devices under some special Multi-screen mode
        val uid = currentInputBinding?.uid ?: return
        Timber.d("onUnbindInput: uid=$uid")
        postFcitxJob {
            deactivate(uid)
        }
    }

    override fun onDestroy() {
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        super.onDestroy()
        // Fcitx might be used in super.onDestroy()
        FcitxDaemon.disconnect(javaClass.name)
    }

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
        }
        dialog.setOnDismissListener {
            showingDialog = null
        }
        dialog.show()
        showingDialog = dialog
    }

    /**
     * 强制写入 pinyin 性能优化配置。在 fcitx 线程（postFcitxJob）中调用。
     * 先读取当前配置，仅覆盖性能相关的 key，保留其余用户配置。
     * 核心优化：
     *   Prediction=False    — 关闭预测词，消除每次提交后的 trie 全词典扫描
     *   PredictionSize=5    — 即使预测词开启，只搜 10 个（maxSize*2）而非默认 98
     *   PageSize=5          — 减少候选词排序计算量
     *   SpellEnabled=False  — 关闭英文候选词处理
     *   SymbolsEnabled=False — 关闭符号候选词查询
     *   Number of sentence=1 — Viterbi 解码只保留最优路径
     */
    private suspend fun FcitxAPI.applyPinyinPerfConfig() {
        try {
            val current = getImConfig("pinyin")
            val updates = mapOf(
                "Prediction" to "False",
                "PredictionSize" to "5",
                "PageSize" to "5",
                "SpellEnabled" to "False",
                "SymbolsEnabled" to "False",
                "Number of sentence" to "1",
                "VAsQuickphrase" to "False"
            )
            updates.forEach { (key, value) ->
                current.getOrCreate(key).value = value
            }
            setImConfig("pinyin", current)
            Timber.i("[IMS] applyPinyinPerfConfig: applied ${updates.size} perf settings to pinyin")
        } catch (e: Exception) {
            Timber.w("[IMS] applyPinyinPerfConfig: failed: ${e.message}")
        }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val DeleteSurroundingFlag = "org.fcitx.fcitx5.android.DELETE_SURROUNDING"
    }
}
