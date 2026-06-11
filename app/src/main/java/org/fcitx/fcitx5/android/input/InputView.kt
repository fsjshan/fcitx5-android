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

    // 密码输入框状态跟踪
    private var isPasswordFieldCleared = false  // 标记密码输入框是否已被��空

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
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 22f // 设置字号为22sp
            setTextColor(0xFFFFFFFF.toInt()) // 白色文字
            setHintTextColor(0xFF888888.toInt()) // 灰色提示文字
            
            // 设置单行显示，上下居中
            setSingleLine(true)
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
            // 添加当前内容显示的EditText到最顶部 - 设置1724x84px尺寸
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
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
        // 【Fix】将 syncFromTargetInputField 推迟，避免在 onMeasure 期间触发 Binder IPC
        post { syncFromTargetInputField() }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
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
        // 尝试直接同步光标位置，避免不必要的全量文本同步
        if (!isSyncing) {
            try {
                // 对于密码输入框，始终使用全量同步以确保状态正确
                if (service.isPasswordInputType()) {
                    syncFromTargetInputField()
                    return
                }

                val currentTextLength = currentContentEditText.length()
                // 检查光标位置是否在当前文本范围内
                if (start <= currentTextLength && end <= currentTextLength) {
                    // 如果光标位置已经正确，则无需操作
                    if (currentContentEditText.selectionStart == start && currentContentEditText.selectionEnd == end) {
                        return
                    }
                    
                    isSyncing = true
                    try {
                        currentContentEditText.setSelection(start, end)
                        // 如果成功设置了光标，我们假设不需要全量同步
                        return
                    } finally {
                        isSyncing = false
                    }
                }
            } catch (e: Exception) {
                Timber.w("Failed to optimize selection update: ${e.message}")
            }
        }
        
        // 如果上面的优化路径没走通（例如光标越界），或者发生了异常，回退到全量同步
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
     * 重置密码输入框状态，在切换到新的输入框时调用
     */
    fun resetPasswordFieldState() {
        isPasswordFieldCleared = false
        Timber.d("Password field state reset")
    }

    /**
     * 同步目标输入框的内容到当前EditText
     * 对于密码输入框，实现一次性清空和明文同步
     */
    fun syncFromTargetInputField() {
        if (isSyncing) return
        
        isSyncing = true
        try {
            // 对于密码输入框的特殊处理
            if (service.isPasswordInputType()) {
                // 只在首次进入密码输入框时清空一次
                if (!isPasswordFieldCleared) {
                    Timber.d("First time entering password field, clearing content")
                    
                    // 清空目标输入框的内容（一次性操作）
                    val ic = service.currentInputConnection
                    if (ic != null) {
                        try {
                            ic.beginBatchEdit()
                            // 获取当前文本长度并选择所有内容
                            val beforeCursor = ic.getTextBeforeCursor(1000, 0)?.length ?: 0
                            val afterCursor = ic.getTextAfterCursor(1000, 0)?.length ?: 0
                            val totalLength = beforeCursor + afterCursor
                            
                            // 选择所有文本并清空
                            ic.setSelection(0, totalLength)
                            ic.commitText("", 1)
                            ic.endBatchEdit()
                        } catch (e: Exception) {
                            Timber.w("Failed to clear password field: ${e.message}")
                        }
                    }
                    
                    // 清空顶端EditText和密码缓存
                    currentContentEditText.setText("")
                    currentContentEditText.setSelection(0)
                    service.clearPasswordCache()
                    
                    // 标记已清空，避免重复清空
                    isPasswordFieldCleared = true
                    Timber.d("Password field cleared once, ready for input")
                    return
                }
                
                // 后续输入时，正常同步密码明文到顶端EditText
                val targetContent = service.getTargetInputFieldContent()  // 这会返回明文
                val cursorPosition = service.getTargetInputFieldCursorPosition()
                
                // 检查内容长度，避免过长内容
                if (targetContent.length > 2000) {
                    Timber.w("Password content too long (${targetContent.length}), truncating to 2000 chars")
                    val truncatedContent = targetContent.substring(0, 2000)
                    currentContentEditText.setText(truncatedContent)
                    currentContentEditText.setSelection(minOf(cursorPosition, truncatedContent.length))
                } else {
                    // 同步密码明文到顶端EditText，确保内容和光标位置都正确同步
                    val currentEditTextContent = currentContentEditText.text?.toString() ?: ""
                    val currentEditTextCursor = try {
                        currentContentEditText.selectionStart
                    } catch (e: Exception) {
                        0
                    }
                    
                    // 检查内容或光标位置是否需要同步
                    if (currentEditTextContent != targetContent || currentEditTextCursor != cursorPosition) {
                        currentContentEditText.setText(targetContent)
                        val safePosition = minOf(maxOf(cursorPosition, 0), targetContent.length)
                        currentContentEditText.setSelection(safePosition)
                        Timber.d("Password plaintext synced to EditText: content=${targetContent.length} chars, cursor=$safePosition")
                    }
                }
                return
            }
            
            // 非密码输入框的正常同步逻辑
            val targetContent = service.getTargetInputFieldContent()
            val cursorPosition = service.getTargetInputFieldCursorPosition()
            
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
                    val safePosition = minOf(cursorPosition, targetContent.length)
                    currentContentEditText.setSelection(safePosition)
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
                    val safePosition = minOf(maxOf(cursorPosition, 0), currentText.length)
                    ic.setSelection(safePosition, safePosition)
                    
                    ic.endBatchEdit()
                } catch (e: Exception) {
                    Timber.w("Failed to sync to target input field: ${e.message}")
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
        
        val ic = service.currentInputConnection ?: return
        
        try {
            // 优化：不再获取全量文本进行比对，直接同步光标位置
            // 这是一个性能关键路径，因为每次光标移动都会触发
            
            // 简单的边界检查，实际上InputConnection.setSelection会处理越界问题，
            // 但我们在本地做一些基本的保护是好的
            val safeSelStart = maxOf(selStart, 0)
            val safeSelEnd = maxOf(selEnd, 0)
            
            isSyncing = true
            try {
                // 直接同步光标位置到目标输入框
                // 移除昂贵的getTargetInputFieldContent()和getCurrentContent()调用
                ic.setSelection(safeSelStart, safeSelEnd)
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
