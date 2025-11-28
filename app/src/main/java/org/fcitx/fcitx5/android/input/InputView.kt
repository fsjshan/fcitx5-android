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

    // 用于显示当前EditText内容的EditText
    private val currentContentEditText = editText {
        hint = ""
        isEnabled = true
        isFocusable = true
        isFocusableInTouchMode = true
        setPadding(dp(16), dp(12), dp(16), dp(12))
        textSize = 18f
        setTextColor(0xFFFFFFFF.toInt()) // 白色文字
        setHintTextColor(0xFF888888.toInt()) // 灰色提示文字
        // 设置背景色为#17171A，去除圆角
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.setColor(0xFF17171A.toInt()) // 背景色#17171A
//        drawable.cornerRadius = 0f // 去除圆角
        background = drawable
        
        // 添加文本变化监听器，实时同步到目标输入框
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // 当EditText内容改变时，同步到目标输入框（避免循环同步）
                if (!isSyncing) {
                    syncToTargetInputField()
                }
            }
        })
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
            // 清空内容并隐藏输入法
            clearCurrentContent()
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
    private val themedContext = context.withTheme(R.style.Theme_InputViewTheme)
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

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

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
            add(currentContentEditText, lParams(dp(1724), dp(84)) {
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
                marginStart = dp(8) // 左间距
                marginEnd = dp(8) // 右间距
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
        // 初始同步目标输入框内容到顶端EditText
        syncFromTargetInputField()
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
        return currentContentEditText.text.toString()
    }

    /**
     * 清空当前内容EditText
     */
    fun clearCurrentContent() {
        currentContentEditText.setText("")
    }

    /**
     * 同步目标输入框的内容到当前EditText
     */
    fun syncFromTargetInputField() {
        if (isSyncing) return
        
        isSyncing = true
        try {
            val targetContent = service.getTargetInputFieldContent()
            val cursorPosition = service.getTargetInputFieldCursorPosition()
            
            // 检查内容长度，避免过长内容
            if (targetContent.length > 5000) {
                Timber.w("Target content too long (${targetContent.length}), truncating to 5000 chars")
                val truncatedContent = targetContent.substring(0, 5000)
                currentContentEditText.setText(truncatedContent)
                currentContentEditText.setSelection(minOf(cursorPosition, truncatedContent.length))
            } else {
                // 只有当内容不同时才更新，避免不必要的操作
                if (currentContentEditText.text.toString() != targetContent) {
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
        
        val currentText = getCurrentContent()
        val cursorPosition = currentContentEditText.selectionStart
        
        // 检查内容长度，避免过长内容
        if (currentText.length > 5000) {
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
                
                // 选择所有文本
                ic.setSelection(0, targetContent.length)
                
                // 替换为新内容
                ic.commitText(currentText, 1)
                
                // 设置光标位置
                val safePosition = minOf(cursorPosition, currentText.length)
                ic.setSelection(safePosition, safePosition)
                
                ic.endBatchEdit()
            } catch (e: Exception) {
                Timber.w("Failed to sync to target input field: ${e.message}")
            } finally {
                isSyncing = false
            }
        }
    }

    override fun onDetachedFromWindow() {
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
