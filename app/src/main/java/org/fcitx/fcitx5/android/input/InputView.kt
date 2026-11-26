/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.View.OnClickListener
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.unset
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.button
import splitties.views.dsl.core.editText
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.withTheme
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import timber.log.Timber

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    // 同步标志，防止无限循环
    private var isSyncing = false

    // 【Fix】顶部框光标权威标志。
    // 目标 App 的输入框（尤其是 WebView / 部分自绘输入框）在收到 setSelection 后
    // 常常立刻回调一个"旧"的 onUpdateSelection，或在 commitText 后把光标留在
    // 插入点+1 的位置。若每次都无条件采纳目标框上报的光标，顶部框光标就会被拉走，
    // 表现为"光标跳变"、"后续字符追加到末尾"。
    //
    // 但"顶部框一操作就无条件权威"会走向另一个极端：目标框若忽略 setSelection，
    // 顶部框光标被锁死，实际插入位置不变。因此分两步：
    //   1) 顶部框光标/文本变化 → 置 pending（见 markTopCursorPending）；
    //   2) 目标框随后的 onUpdateSelection 与之吻合 → 升级为权威；
    //      不吻合 → 丢弃 pending，跟随目标框。
    // 权威状态在用户点击目标 App 输入框（onStartInput）时撤销。
    //
    // 【Fix】为避免"目标框回声"把 pending 过早拒掉（顶部框刚 setSelection，
    // 目标框立刻回调一个旧光标），顶部框发出 setSelection 时置 topCursorSyncIssued，
    // 其后的第一个回调视为回声：一致则确认权威，不一致则忽略，只有再往后的回调才拒绝。
    private var topEditTextAuthoritative = false

    /** 顶部框已把光标 setSelection 给目标框，等待其回声回调（见 onUpdateSelection）。 */
    private var topCursorSyncIssued = false

    /** 顶部框光标是否已 setSelection 给目标框、等待其回声回调。 */
    fun isTopCursorSyncIssued(): Boolean = topCursorSyncIssued

    /** 标记"顶部框已把光标发给目标框"。 */
    fun markTopCursorSyncIssued() {
        topCursorSyncIssued = true
    }

    /** 回声回调已消费，后续回调恢复正常裁决。 */
    fun markTopCursorSyncEchoConsumed() {
        topCursorSyncIssued = false
    }

    /**
     * 顶部 EditText 光标是否应作为权威来源（供 [FcitxInputMethodService] 判断）。
     */
    fun isTopEditTextAuthoritative(): Boolean = topEditTextAuthoritative

    /**
     * 顶部 EditText 当前光标位置（越界时收敛到 [0, length]）。
     */
    fun getTopEditTextCursor(): Int {
        val et = currentContentEditText
        val len = et.length()
        val start = try {
            et.selectionStart
        } catch (e: Exception) {
            -1
        }
        val end = try {
            et.selectionEnd
        } catch (e: Exception) {
            -1
        }
        // 有选区时以选区起点为插入点
        val cursor = if (start >= 0) minOf(start, end.takeIf { it >= 0 } ?: start) else len
        return cursor.coerceIn(0, len)
    }

    /**
     * 顶部 EditText 当前选区（供 [FcitxInputMethodService] 使用）。
     */
    fun getTopEditTextSelection(): Pair<Int, Int> {
        val et = currentContentEditText
        val len = et.length()
        val start = try {
            et.selectionStart
        } catch (e: Exception) {
            -1
        }
        val end = try {
            et.selectionEnd
        } catch (e: Exception) {
            -1
        }
        if (start < 0 || end < 0) return len to len
        return start.coerceIn(0, len) to end.coerceIn(0, len)
    }

    /**
     * 取消顶部框的光标权威（例如目标 App 输入框重新获得输入会话）。
     */
    fun clearTopEditTextAuthoritative() {
        topEditTextAuthoritative = false
        topCursorPending = false
        topCursorSyncIssued = false
    }

    /**
     * 【Fix】顶部框光标权威的"待确认"状态。
     *
     * 顶部框移动光标时，用户意图是"把插入点放到这里"，但此刻我们并不知道
     * 目标 App 输入框会不会接受这个位置（部分 WebView / 自绘输入框会忽略
     * setSelection，或把光标改写到别处）。若立刻无条件以顶部框为准，
     * 就会出现"顶部框光标动了、实际插入位置不变"。
     *
     * 因此：顶部框光标变化后先置 pending，等目标框上报的光标与之吻合
     * （见 [FcitxInputMethodService.onUpdateSelection]）才升级为权威；
     * 若目标框上报了别的位置，说明它没有接受，则丢弃 pending 并跟随目标框。
     */
    private var topCursorPending = false

    /** 顶部框光标是否处于"待目标框确认"状态。 */
    fun isTopCursorPending(): Boolean = topCursorPending

    /**
     * 记录"顶部框光标已变化，等待目标框确认"，并清掉旧的权威状态。
     */
    fun markTopCursorPending() {
        topCursorPending = true
        topEditTextAuthoritative = false
        topCursorSyncIssued = false
    }

    /**
     * 目标框上报的光标与顶部框一致 → 确认权威，后续滞后回调不再覆盖顶部框。
     */
    fun confirmTopEditTextAuthoritative() {
        topCursorSyncIssued = false
        if (!topCursorPending) return
        topCursorPending = false
        topEditTextAuthoritative = true
    }

    /**
     * 目标框上报的光标与顶部框不一致 → 目标框没有接受顶部框光标，
     * 丢弃 pending，回到"跟随目标框"的模式。
     */
    fun rejectTopCursorPending() {
        topCursorPending = false
        topCursorSyncIssued = false
    }

    // 【Fix】原 isPasswordFieldCleared 字段已删除：密码框改为完全由明文缓存
    // （cachedPlaintextPassword / passwordCursorPosition）驱动，
    // 不再需要"是否已清空"这类一次性状态。

    // 【Fix】提前声明 themedContext，currentContentEditText 构造时需要用到。
    // 原位置在类体靠后，导致 Kotlin 属性初始化顺序错误（编译报 "must be initialized"）。
    private val themedContext = context.withTheme(R.style.Theme_InputViewTheme)

    // 用于显示当前输入框内容的 EditText。
    // 【Fix】使用原生 EditText + themedContext，避免 AppCompatEditText 在非 AppCompat Theme
    // 下 onMeasure 时触发 ThemeUtils 检查（阻塞主线程 500~800ms）。
    private val currentContentEditText = object : android.widget.EditText(themedContext) {
        init {
            hint = ""
            isEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
            // 【Fix】顶部框获取焦点时，TextView 默认会调用 InputMethodManager.showSoftInput()。
            // 本 View 处在 IME 自己的 SoftInputWindow 内（FLAG_ALT_FOCUSABLE_IM），
            // 该调用在多数 ROM 上是 no-op，但部分 ROM 会导致 IME 自我重入
            // （重新走 onStartInput → 与 syncFromTargetInputField / composing 状态产生竞态）。
            // 顶部框的文本由 syncFromTargetInputField 驱动，无需自己唤起输入法，故关闭。
            showSoftInputOnFocus = false
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 22f // 设置字号为22sp
            setTextColor(0xFFFFFFFF.toInt()) // 白色文字
            setHintTextColor(0xFF888888.toInt()) // 灰色提示文字

            // 设置单行显示，上下居中
            gravity = android.view.Gravity.CENTER_VERTICAL
            val drawable = android.graphics.drawable.GradientDrawable()
            drawable.setColor(0xFF17171A.toInt())
            background = drawable
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val cursorDrawable = android.graphics.drawable.GradientDrawable()
                    cursorDrawable.setColor(0xFF0080FF.toInt())
                    cursorDrawable.setSize(dp(4), dp(20))
                    textCursorDrawable = cursorDrawable
                }
            } catch (e: Exception) {}
            highlightColor = 0x660080FF.toInt()
            // 【Fix】删除反射调用（setTextSelectHandle/Left/Right + mEditor fallback）。
            // 原来三次 getDeclaredMethod+setAccessible+invoke 在主线程同步执行，每次约 20~50ms，
            // 且第一次 getDeclaredMethod 会触发 JVM class 扫描，加上两层 try-catch，
            // 累计在首次 onMeasure 前额外消耗约 100~200ms。
            // 选择手柄颜色通过 themedContext + Theme.InputViewTheme 的 theme attr 统一设置。
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!isSyncing) {
                        syncToTargetInputField()
                    }
                }
            })
        }

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            if (!isSyncing) {
                // 用户在顶部框内移动光标 / 编辑后，顶部框进入"光标待确认"状态，
                // 目标框随后的 onUpdateSelection 若吻合则升级为权威，
                // 后续目标框上报的旧光标不再覆盖它。
                syncCursorToTargetInputField(selStart, selEnd)
            }
        }
    }

    // EditText左端填充矩形 - 与EditText同高度，背景色#17171A
    private val leftFillRect = view(::View) {
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.setColor(0xFF17171A.toInt()) // 背景色#17171A
        background = drawable
    }

    // EditText右端填充矩形 - 与EditText同高度，背景色#17171A
    private val rightFillRect = view(::View) {
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.setColor(0xFF17171A.toInt()) // 背景色#17171A
        background = drawable
    }

    // 取消按钮（左上角）- 按照设计图样式：17px圆角，66x34px尺寸，#303033背景色
    private val cancelButton = button {
        text = "取消"
        setPadding(0, 0, 0, 0)
        setTextColor(0xCCFFFFFF.toInt()) // 白色文字
        textSize = 17f
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.setColor(0xFF303033.toInt()) // 背景色#303033
        drawable.cornerRadius = dp(17).toFloat() // 17px圆角
        background = drawable
        setOnClickListener {
            // 清空顶部EditText内容
//            clearCurrentContent()
//
//            // 清空目标输入框内容
//            val ic = service.currentInputConnection
//            if (ic != null) {
//                try {
//                    ic.beginBatchEdit()
//                    // 获取当前文本并删除所有内容
//                    val currentText = ic.getTextBeforeCursor(10000, 0) ?: ""
//                    val afterText = ic.getTextAfterCursor(10000, 0) ?: ""
//                    val totalLength = currentText.length + afterText.length
//
//                    if (totalLength > 0) {
//                        // 选择所有文本并删除
//                        ic.setSelection(0, totalLength)
//                        ic.deleteSurroundingText(0, totalLength)
//                    }
//                    ic.endBatchEdit()
//
//                    // 清空密码缓存
//                    service.clearPasswordCache()
//
//                    // 重置密码状态
//                    resetPasswordFieldState()
//                } catch (e: Exception) {
//                    Timber.w("Failed to clear target input field: ${e.message}")
//                }
//            }
            
            service.requestHideSelf(0)
        }
    }

    

    // 确定按钮（右上角）- 按照设计图样式：17px圆角，66x34px尺寸，#303033背景色
    private val confirmButton = button {
        text = "确定"
        setPadding(0, 0, 0, 0)
        setTextColor(0xCCFFFFFF.toInt()) // 白色文字
        textSize = 14f
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.setColor(0xFF303033.toInt()) // 背景色#303033
        drawable.cornerRadius = dp(17).toFloat() // 17px圆角
        background = drawable
        setOnClickListener {
            // 执行回车键操作，handleReturnKey方法中已包含隐藏键盘的逻辑
            service.handleReturnKey()
        }
    }

    private val scope = DynamicScope()
    // themedContext 已在 currentContentEditText 之前声明，此处不再重复
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()
    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
    )

    private val keyboardHeightPx: Int
        get() {
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            return resources.displayMetrics.heightPixels * percent / 100
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
    }

    val keyboardView: View

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view hasbeen created before it receives any broadcast
        // 【Fix】createView = false：避免在 InputView 构造时立刻触发 TextKeyboard 构造
        // （40 个 KeyView × drawable 创建 → 大量 GC → onMeasure 卡顿）
        windowManager.addEssentialWindow(keyboardWindow, createView = false)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // 【Fix】将 attachWindow 推迟到第一帧 measure/layout 完成后，
        // 避免 TextKeyboard 的 40 个 KeyView + drawable 构造发生在首次 onMeasure 期间。
        // 键盘 View 创建推迟约 16ms（一帧），对用户无感知但彻底消除 GC 阻塞 onMeasure。
        post { windowManager.attachWindow(KeyboardWindow) }
        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

//        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                topOfParent()
                centerHorizontally()
                topMargin = dp(8) // 与EditText顶部对齐
                val drawable = android.graphics.drawable.GradientDrawable()
                drawable.setColor(0xFF17171A.toInt())
                background = drawable
            })
            // 添加左端填充矩形 - 与EditText同高度，背景色#17171A
            add(leftFillRect, lParams(0, dp(84)) {
                topOfParent()
                startOfParent()
                endToStartOf(currentContentEditText)
                topMargin = dp(8)
            })
            // 添加右端填充矩形 - 与EditText同高度，背景色#17171A
            add(rightFillRect, lParams(0, dp(84)) {
                topOfParent()
                startToEndOf(currentContentEditText)
                endOfParent()
                topMargin = dp(8)
            })
            // 添加当前内容显示的EditText到最顶部 - 设置284x84px尺寸
            add(currentContentEditText, lParams(dp(284), dp(84)) {
                topOfParent()
                centerHorizontally()
                topMargin = dp(8)
            })
            // 添加取消按钮到左端填充矩形中居中 - 66x34px尺寸，#303033背景色
            add(cancelButton, lParams(dp(66), dp(34)) {
                topOfParent()
                startOfParent()
                endToStartOf(currentContentEditText)
                topMargin = dp(33) // 垂直居中：(84-34)/2 + 8 = 33
            })
            // 添加确定按钮到右端填充矩形中居中 - 66x34px尺寸，#303033背景色
            add(confirmButton, lParams(dp(66), dp(34)) {
                topOfParent()
                startToEndOf(currentContentEditText)
                endOfParent()
                topMargin = dp(33) // 垂直居中：(84-34)/2 + 8 = 33
            })
            
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                below(currentContentEditText)
                val drawable = android.graphics.drawable.GradientDrawable()
                drawable.setColor(0xFF17171A.toInt())
                background = drawable
                centerHorizontally()
                topMargin = dp(0) // 与EditText的间距
                marginStart = dp(4) // 左间距
                marginEnd = dp(4) // 右间距
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }

        updateKeyboardSize()

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            above(keyboardView)
            centerHorizontally()
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
    }

    private fun updateKeyboardSize() {
        windowManager.view.updateLayoutParams {
            height = keyboardHeightPx
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    /**
     * 让顶部输入框获得 View 级焦点，使光标自动出现在顶部框内。
     *
     * 【背景】IME 的 SoftInputWindow 带 FLAG_NOT_FOCUSABLE，永远拿不到窗口焦点，
     * 但 Editor.shouldBlink() 只检查 isCursorVisible() && isFocused() && selStart == selEnd，
     * 并不检查 hasWindowFocus()，且 suspendBlink() 仅在"窗口焦点 true→false"的转变时刻触发
     * （本窗口从未有过窗口焦点，故从未触发）。因此 View 级焦点足以让光标正常闪烁，
     * 无需改动任何 window flag。
     *
     * 【为何需要手动调】touch mode 下 ViewRootImpl 不会自动挑选 focusable view
     * （自动聚焦只发生在按键导航场景），此前只有 TextView.onTouchEvent 内部那次隐式
     * requestFocus() 能让光标出现，表现为"必须手动点一次顶部框"。
     *
     * 【刻意不改 selection】requestFocus() 本身不改变 selection
     * （Editor.onFocusChanged 仅在 selStart < 0 时兜底置 0），
     * 而 setText/setSelection 由 [syncFromTargetInputField] 负责。
     * 因此这里无需 isSyncing 包裹，也不会通过 onSelectionChanged
     * 反向把目标 App 的光标拖到末尾。
     *
     * 幂等：三处调用点重复触发无副作用。
     */
    private fun focusTopEditText() {
        val et = currentContentEditText
        // 未 attach 时焦点无法落地到 ViewRootImpl；非 VISIBLE 时 requestFocus 直接返回 false
        if (!et.isAttachedToWindow) return
        if (et.visibility != View.VISIBLE) return
        if (!et.isFocused) {
            et.requestFocus()
            return
        }
        // 【Fix】键盘隐藏再弹出（DecorView visibility 8 → 0）时，et.isFocused 仍为 true，
        // 但 Editor 的 blink 已被窗口焦点变化 / 窗口重绘挂起，光标不再绘制，
        // 表现为"顶部框有焦点但看不到光标"。
        // 这里通过 setCursorVisible(false/true) 强制重走 showCursor()，
        // 不改变 selection，也不会触发 onSelectionChanged。
        et.setCursorVisible(false)
        et.setCursorVisible(true)
        // 兜底：若 selection 尚未建立（selStart < 0），Editor 不会绘制光标，
        // 此时把光标收敛到文本末尾，保证光标可见且位置合理。
        if (et.selectionStart < 0) {
            val len = et.length()
            isSyncing = true
            try {
                et.setSelection(len)
            } catch (e: Exception) {
                Timber.w("Failed to restore top EditText selection: ${e.message}")
            } finally {
                isSyncing = false
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 覆盖 startInput 的 post 块执行时尚未 attach 的竞态。
        // 【必须 post】ViewGroup.dispatchAttachedToWindow() 会先调用自身的
        // onAttachedToWindow()，之后才遍历 attach 子 View，所以此刻
        // currentContentEditText.isAttachedToWindow 仍为 false，直接调用会被守卫挡掉。
        post { focusTopEditText() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            // 覆盖 InputView 被复用、未重新 attach、仅 GONE/INVISIBLE → VISIBLE 的二次弹出场景。
            // 同样 post：首次 attach 链路中这个回调也早于子 View attach。
            post { focusTopEditText() }
        }
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
        // 【Fix】新的输入会话开始（用户点击了目标 App 的输入框），
        // 此时以目标框的内容与光标为准，撤销顶部框的权威与待确认状态。
        clearTopEditTextAuthoritative()
        // 【Fix】将 syncFromTargetInputField 推迟，避免在 onMeasure 期间触发 Binder IPC
        post {
            syncFromTargetInputField()
            // 文本/光标同步完成后再获焦，确保首帧光标就落在正确位置
            focusTopEditText()
        }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                // 直接传已知的空/非空状态，避免 runImmediately 读 fcitx 缓存
                preeditEmptyState.updatePreeditEmptyStateByKnown(clientPreeditEmpty = it.data.isEmpty())
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                // 直接传已知的空/非空状态，避免 runImmediately 读 fcitx 缓存
                preeditEmptyState.updatePreeditEmptyStateByKnown(preeditEmpty = it.data.preedit.isEmpty())
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
        // 【Fix】密码输入框不再做全量 syncFromTargetInputField()：
        // 原实现每次 onUpdateSelection 都全量同步（3 次 Binder IPC + setText），
        // 会把用户在顶端框里刚移动的光标又拉回目标框旧光标位置，
        // 导致"顶部框光标动了、实际插入位置不变"。
        // 密码框的内容与光标一致性由 handleCursorUpdate 维护的 passwordCursorPosition 保证。
        // 正在拼音组字时（composing 不为空），顶端 EditText 不需要同步目标框内容，
        // 避免 3 次 Binder IPC 阻塞主线程，导致 preedit 预展示延迟
        if (service.isComposing) return
        if (isSyncing) return
        // 密码框：目标框上报的是掩码文本的坐标，与顶端明文坐标不可直接映射，
        // 且此时顶端框光标通常就是用户刚设定的值，直接忽略，避免把光标拉回去。
        if (service.isPasswordInputType()) return
        // 【Fix】顶部框是光标权威来源时，目标框上报的光标多为滞后/被 App 改写过的值，
        // 直接忽略，避免把顶部框光标拉走（表现为输入过程中光标跳变）。
        // 注意：pending 状态（顶部框刚移动光标、目标框尚未确认）不能在此拦截，
        // 否则第 2 步"目标框确认/拒绝"的判断永远拿不到目标框的真实光标。
        if (isTopEditTextAuthoritative()) {
            Timber.v("updateSelection ignored: top EditText is authoritative, target=($start,$end)")
            return
        }
        try {
            val currentTextLength = currentContentEditText.length()
            if (start <= currentTextLength && end <= currentTextLength) {
                if (currentContentEditText.selectionStart == start && currentContentEditText.selectionEnd == end) {
                    return
                }
                isSyncing = true
                try {
                    currentContentEditText.setSelection(start, end)
                    return
                } finally {
                    isSyncing = false
                }
            }
        } catch (e: Exception) {
            Timber.w("Failed to optimize selection update: ${e.message}")
        }
        // fallback：光标越界时才做全量同步
        syncFromTargetInputField()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    /**
     * 更新当前内容显示EditText的文本
     */
    fun updateCurrentContent(text: String) {
        currentContentEditText.setText(text)
        currentContentEditText.setSelection(text.length) // 将光标移到末尾
    }

    /**
     * 获取当前内容EditText中的文本
     */
    fun getCurrentContent(): String {
        return try {
            currentContentEditText.text?.toString() ?: ""
        } catch (e: Exception) {
            Timber.w("Failed to get current content: ${e.message}")
            ""
        }
    }

    /**
     * 清空当前内容EditText
     */
    fun clearCurrentContent() {
        currentContentEditText.setText("")
    }

    /**
     * 重置密码输入框状态，在切换到新的输入框时调用。
     *
     * 【Fix】密码框状态全部收敛到明文缓存（由 [FcitxInputMethodService.clearPasswordCache]
     * 在 onStartInput 中清空），这里不再持有任何独立状态，仅保留调用点以兼容既有流程。
     */
    fun resetPasswordFieldState() {
        Timber.v("Password field state reset")
    }

    /**
     * 【Fix】按明文缓存刷新顶部框（密码框唯一的内容/光标来源）。
     *
     * 密码框的目标 App 输入框里只有掩码文本，长度与明文缓存不一定相等，
     * 因此绝不能回读目标框内容/光标来驱动顶部框：
     *   - 内容：一律取 [FcitxInputMethodService.getTargetInputFieldContent]（明文缓存）
     *   - 光标：一律取 [FcitxInputMethodService.getPasswordCursorPosition]
     *
     * 只在内容变化时 setText + 重设光标；内容一致时保留用户当前光标，
     * 避免把用户在顶部框里刚移动的光标拉回去。
     */
    fun refreshPasswordTopEditText() {
        if (isSyncing) return
        val targetContent = service.getTargetInputFieldContent() // 明文缓存
        val cursorPosition = service.getPasswordCursorPosition()
        val safePosition = minOf(maxOf(cursorPosition, 0), targetContent.length)
        isSyncing = true
        try {
            val currentEditTextContent = currentContentEditText.text?.toString() ?: ""
            if (currentEditTextContent != targetContent) {
                currentContentEditText.setText(targetContent)
                currentContentEditText.setSelection(safePosition)
                Timber.d("Password plaintext synced to EditText: ${targetContent.length} chars, cursor=$safePosition")
            } else if (currentContentEditText.selectionStart != safePosition) {
                // 内容一致但缓存光标被本端逻辑改动（插入/退格/删除后），需要跟随。
                currentContentEditText.setSelection(safePosition)
                Timber.v("Password cursor synced to $safePosition")
            }
        } catch (e: Exception) {
            Timber.w("Failed to refresh password top EditText: ${e.message}")
        } finally {
            isSyncing = false
        }
    }

    /**
     * 同步目标输入框的内容到当前EditText
     * 对于密码输入框，实现一次性清空和明文同步
     */
    fun syncFromTargetInputField() {
        if (isSyncing) return
        // 正在拼音组字时跳过：composing span 期间顶端 EditText 内容无需同步，
        // 避免 3 次 Binder IPC 与 preedit 更新竞争主线程
        if (service.isComposing) return
        // 【Fix】顶部框为光标权威来源时，光标由用户/顶部框主导，
        // 但内容仍需回读目标框：commitText / 退格 / 删除都只改了目标框，
        // 若这里直接 return，顶部框内容会停在旧文本上（表现为"顶部框不更新"）。
        // 因此只跳过光标覆盖，内容仍按目标框刷新（见下方 keepTopCursor）。

        // 【Fix】密码框完全不走本方法：目标框里只有掩码（"•••"），
        // 回读掩码再写进顶部框会让用户看到掩码而不是明文，
        // 且掩码长度与明文长度不等，光标无法映射。
        // 密码框的顶部框内容/光标一律由 [refreshPasswordTopEditText] 按明文缓存刷新。
        if (service.isPasswordInputType()) {
            refreshPasswordTopEditText()
            return
        }

        isSyncing = true
        try {
            // 非密码输入框的正常同步逻辑
            val targetContent = service.getTargetInputFieldContent()
            val cursorPosition = service.getTargetInputFieldCursorPosition()

            // 【Fix】顶部框为光标权威时，光标由用户主导，不再用目标框光标覆盖。
            // 目标框光标（getTextBeforeCursor 长度）在部分 App 上滞后或被改写，
            // 无条件采用会把顶部框光标拉走，表现为"输入过程中光标跳变"。
            val keepTopCursor = isTopEditTextAuthoritative()
            val topCursor = if (keepTopCursor) getTopEditTextCursor() else 0

            // 检查内容长度，避免过长内容
            if (targetContent.length > 2000) {
                Timber.w("Target content too long (${targetContent.length}), truncating to 2000 chars")
                val truncatedContent = targetContent.substring(0, 2000)
                currentContentEditText.setText(truncatedContent)
                currentContentEditText.setSelection(minOf(cursorPosition, truncatedContent.length))
            } else {
                // 非密码框，正常内容比较
                if (currentContentEditText.text?.toString() != targetContent) {
                    currentContentEditText.setText(targetContent)

                    // 设置光标位置，确保不超出文本长度
                    val safePosition = if (keepTopCursor) {
                        minOf(topCursor, targetContent.length)
                    } else {
                        minOf(cursorPosition, targetContent.length)
                    }
                    currentContentEditText.setSelection(safePosition)
                } else if (!keepTopCursor) {
                    // 内容一致且顶部框不是权威来源时，才跟随目标框光标
                    val safePosition = minOf(cursorPosition, targetContent.length)
                    if (currentContentEditText.selectionStart != safePosition) {
                        currentContentEditText.setSelection(safePosition)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w("Failed to sync from target input field: ${e.message}")
        } finally {
            isSyncing = false
        }
    }

    /**
     * 将当前EditText的内容同步到目标输入框
     */
    fun syncToTargetInputField() {
        if (isSyncing) return
        // 【Fix】密码框：顶部框是明文、目标框是掩码，两侧文本永远不相等，
        // 若走下面的"内容不同 → 全量替换"逻辑，会把明文直接写进密码框，
        // 而且每次输入都会触发一次全量替换 + setSelection，导致内容/光标错乱。
        // 密码框的顶部框只用于展示与定位，实际写入由 commitText/退格路径完成，
        // 这里仅把顶部框光标记录到明文缓存。
        if (service.isPasswordInputType()) {
            val sel = getTopEditTextSelection()
            if (sel.first == sel.second) {
                service.setPasswordCursorPosition(sel.first)
            }
            return
        }
        // 【Fix】顶部框内容变化代表用户主动编辑，但光标位置是否被目标框接受仍需确认：
        // 这里只进入待确认状态，避免目标框忽略 setSelection 时顶部框光标被"假权威"锁死。
        markTopCursorPending()

        try {
            val currentText = getCurrentContent()
            val cursorPosition = try {
                currentContentEditText.selectionStart
            } catch (e: Exception) {
                0 // 默认光标位置为0
            }
            
            // 检查内容长度，避免过长内容，使用更保守的限制
            if (currentText.length > 2000) {
                Timber.w("Current content too long (${currentText.length}), skipping sync")
                return
            }
            
            // 获取目标输入框的当前内容
            val targetContent = service.getTargetInputFieldContent()
            
            val safePosition = minOf(maxOf(cursorPosition, 0), currentText.length)

            // 如果内容不同，则需要同步
            if (currentText != targetContent) {
                val ic = service.currentInputConnection ?: return
                
                isSyncing = true
                try {
                    ic.beginBatchEdit()
                    
                    // 选择所有文本并替换
                    ic.setSelection(0, targetContent.length)
                    ic.commitText(currentText, 0)
                    
                    // 设置光标位置，确保在有效范围内
                    ic.setSelection(safePosition, safePosition)
                    // 【Fix】内容整体替换后同样标记"已发出"，让回声回调不拒掉 pending
                    markTopCursorSyncIssued()
                    
                    ic.endBatchEdit()
                } catch (e: Exception) {
                    Timber.w("Failed to sync to target input field: ${e.message}")
                } finally {
                    isSyncing = false
                }
            } else {
                // 【Fix】内容一致时也要把光标同步过去。
                // 顶部框内移动光标后，若目标框光标没跟上（部分输入框忽略 setSelection，
                // 或 onUpdateSelection 回调晚于下一次 commitText），
                // 下一次插入就会落在目标框旧光标处，表现为"只有第一个字符插对"。
                val ic = service.currentInputConnection ?: return
                if (service.getTargetInputFieldCursorPosition() == safePosition) return
                isSyncing = true
                try {
                    service.predictSelection(safePosition, safePosition)
                    ic.setSelection(safePosition, safePosition)
                    // 标记"已发出"，让目标框紧随其后的回声回调不会把 pending 拒掉
                    markTopCursorSyncIssued()
                } catch (e: Exception) {
                    Timber.w("Failed to sync cursor to target input field: ${e.message}")
                } finally {
                    isSyncing = false
                }
            }
        } catch (e: Exception) {
            Timber.w("Error in syncToTargetInputField: ${e.message}")
        }
    }

    /**
     * 将当前EditText的光标位置同步到目标输入框
     * 实现光标位置的反向同步 - 以顶端EditText为主导
     */
    fun syncCursorToTargetInputField(selStart: Int, selEnd: Int) {
        if (isSyncing) return
        // 【Fix】顶部框光标变化只代表"用户想放到这里"，先进入待确认状态，
        // 由目标框随后的 onUpdateSelection 决定是否升级为权威（见 markTopCursorPending）。
        markTopCursorPending()

        try {
            val safeSelStart = maxOf(selStart, 0)
            val safeSelEnd = maxOf(selEnd, 0)

            // 【Fix】密码框：顶端 EditText 是明文、目标框是掩码，两者长度可能不同，
            // 直接把顶端框光标 setSelection 到目标框会越界或错位；
            // 密码框只需把光标位置记录到明文缓存（passwordCursorPosition），
            // 由 insertTextInPassword / handlePasswordBackspace 按该位置操作，
            // 目标框光标会在下一次 commitText 时自然跟随。
            if (service.isPasswordInputType()) {
                // 密码框不走 pending/权威确认（两侧坐标不可映射），直接撤销，
                // 避免残留 pending 影响后续普通输入框。
                clearTopEditTextAuthoritative()
                if (safeSelStart == safeSelEnd) {
                    service.setPasswordCursorPosition(safeSelStart)
                    Timber.v("Password cursor recorded: $safeSelStart")
                }
                return
            }

            val ic = service.currentInputConnection ?: return

            // 预先告知 CursorTracker 即将产生此光标事件，使 onUpdateSelection 里
            // selection.consume 能命中预测值，避免触发 fcitx focus(false)/focus(true) 重置
            service.predictSelection(safeSelStart, safeSelEnd)

            isSyncing = true
            try {
                ic.setSelection(safeSelStart, safeSelEnd)
                // 标记"已发出"，让目标框紧随其后的回声回调不会把 pending 拒掉
                markTopCursorSyncIssued()
                Timber.v("Cursor synced to target: start=$safeSelStart, end=$safeSelEnd")
            } catch (e: Exception) {
                Timber.w("Failed to sync cursor to target input field: ${e.message}")
            } finally {
                isSyncing = false
            }
        } catch (e: Exception) {
            Timber.w("Error in syncCursorToTargetInputField: ${e.message}")
        }
    }


    override fun onDetachedFromWindow() {
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
