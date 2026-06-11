/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.keyboard

import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dialog.AddMoreInputMethodsPrompt
import org.fcitx.fcitx5.android.input.dialog.InputMethodPickerDialog
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Reset
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Selection
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener.BackspaceSwipeState.Stopped
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.CommitAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.DeleteSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.FcitxKeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.LangSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.MoveSelectionAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.PickerSwitchAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.QuickPhraseAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.ShowInputMethodPickerAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SpaceLongPressAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.SymAction
import org.fcitx.fcitx5.android.input.keyboard.KeyAction.UnicodeAction
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.switchToNextIME
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import timber.log.Timber

class CommonKeyActionListener :
    UniqueComponent<CommonKeyActionListener>(), Dependent, ManagedHandler by managedHandler() {

    enum class BackspaceSwipeState {
        Stopped, Selection, Reset
    }

    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val preeditState: PreeditEmptyStateComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()

    private var lastPickerType by AppPrefs.getInstance().internal.lastPickerType

    private val kbdPrefs = AppPrefs.getInstance().keyboard

    private val spaceKeyLongPressBehavior by kbdPrefs.spaceKeyLongPressBehavior
    private val langSwitchKeyBehavior by kbdPrefs.langSwitchKeyBehavior

    private var backspaceSwipeState = Stopped

    private val keepComposingIMs = arrayOf("keyboard-us", "unikey")

    private suspend fun FcitxAPI.commitAndReset() {
        if (clientPreeditCached.isEmpty() && inputPanelCached.preedit.isEmpty()) {
            // preedit is empty, there can be prediction candidates
            reset()
        } else if (inputMethodEntryCached.uniqueName in keepComposingIMs) {
            // androidkeyboard clears composing on reset, but we want to commit it as-is
            service.finishComposing()
            reset()
        } else {
            if (!select(0)) reset()
        }
    }

    private fun showInputMethodPicker() {
        fcitx.launchOnReady {
            service.lifecycleScope.launch {
                service.showDialog(InputMethodPickerDialog.build(it, service, context))
            }
        }
    }

    val listener by lazy {
        KeyActionListener { action, _ ->
            Timber.d("[KeyAction] received: ${action::class.simpleName} = $action")
            when (action) {
                is FcitxKeyAction -> service.postFcitxJob {
                    Timber.d("[KeyAction] FcitxKeyAction: act=${action.act} states=${action.states} code=${action.code}")
                    sendKey(action.act, action.states.states, action.code)
                }
                is SymAction -> service.postFcitxJob {
                    Timber.d("[KeyAction] SymAction: sym=${action.sym} states=${action.states}")
                    sendKey(action.sym, action.states)
                }
                is CommitAction -> service.postFcitxJob {
                    Timber.d("[KeyAction] CommitAction: text='${action.text}'")
                    commitAndReset()
                    service.lifecycleScope.launch { service.commitText(action.text) }
                }
                is QuickPhraseAction -> service.postFcitxJob {
                    Timber.d("[KeyAction] QuickPhraseAction: commitAndReset + triggerQuickPhrase")
                    commitAndReset()
                    triggerQuickPhrase()
                }
                is UnicodeAction -> service.postFcitxJob {
                    Timber.d("[KeyAction] UnicodeAction: commitAndReset + triggerUnicode")
                    commitAndReset()
                    triggerUnicode()
                }
                is LangSwitchAction -> {
                    Timber.d("[KeyAction] LangSwitchAction: behavior=$langSwitchKeyBehavior")
                    when (langSwitchKeyBehavior) {
                        LangSwitchBehavior.Enumerate -> {
                            service.postFcitxJob {
                                if (enabledIme().size < 2) {
                                    Timber.d("[KeyAction] LangSwitch Enumerate: only 1 IME, show AddMore prompt")
                                    service.lifecycleScope.launch {
                                        service.showDialog(AddMoreInputMethodsPrompt.build(context))
                                    }
                                } else {
                                    Timber.d("[KeyAction] LangSwitch Enumerate: enumerateIme")
                                    enumerateIme()
                                }
                            }
                        }
                        LangSwitchBehavior.ToggleActivate -> {
                            Timber.d("[KeyAction] LangSwitch ToggleActivate: toggleIme")
                            service.postFcitxJob {
                                toggleIme()
                            }
                        }
                        LangSwitchBehavior.NextInputMethodApp -> {
                            Timber.d("[KeyAction] LangSwitch NextInputMethodApp: switchToNextIME")
                            service.switchToNextIME()
                        }
                    }
                }
                is ShowInputMethodPickerAction -> {
                    Timber.d("[KeyAction] ShowInputMethodPickerAction")
                    showInputMethodPicker()
                }
                is MoveSelectionAction -> {
                    Timber.d("[KeyAction] MoveSelectionAction: start=${action.start} end=${action.end} swipeState=$backspaceSwipeState")
                    when (backspaceSwipeState) {
                        Stopped -> {
                            backspaceSwipeState = if (
                                preeditState.isEmpty &&
                                horizontalCandidate.adapter.total <= 0 // total is -1 on initialization
                            ) {
                                Timber.d("[KeyAction] MoveSelectionAction: preedit empty → applySelectionOffset, state=Selection")
                                service.applySelectionOffset(action.start, action.end)
                                Selection
                            } else {
                                Timber.d("[KeyAction] MoveSelectionAction: preedit non-empty → state=Reset")
                                Reset
                            }
                        }
                        Selection -> {
                            service.applySelectionOffset(action.start, action.end)
                        }
                        Reset -> {}
                    }
                }
                is DeleteSelectionAction -> {
                    Timber.d("[KeyAction] DeleteSelectionAction: totalCnt=${action.totalCnt} swipeState=$backspaceSwipeState")
                    when (backspaceSwipeState) {
                        Stopped -> {}
                        Selection -> {
                            Timber.d("[KeyAction] DeleteSelectionAction: Selection → deleteSelection")
                            service.deleteSelection()
                        }
                        Reset -> if (action.totalCnt < 0) { // swipe left
                            Timber.d("[KeyAction] DeleteSelectionAction: Reset+swipeLeft → reset fcitx")
                            service.postFcitxJob { reset() }
                        }
                    }
                    backspaceSwipeState = Stopped
                }
                is PickerSwitchAction -> {
                    val key = action.key?.also { k -> lastPickerType = k.name }
                        ?: runCatching { PickerWindow.Key.valueOf(lastPickerType) }.getOrNull()
                        ?: PickerWindow.Key.Emoji
              Timber.d("[KeyAction] PickerSwitchAction: key=$key")
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(key)
                    }
                }
                is SpaceLongPressAction -> {
                    Timber.d("[KeyAction] SpaceLongPressAction: behavior=$spaceKeyLongPressBehavior")
                    when (spaceKeyLongPressBehavior) {
                        SpaceLongPressBehavior.None -> {}
                        SpaceLongPressBehavior.Enumerate -> service.postFcitxJob {
                            enumerateIme()
                        }
                        SpaceLongPressBehavior.ToggleActivate -> service.postFcitxJob {
                            toggleIme()
                        }
                        SpaceLongPressBehavior.ShowPicker -> showInputMethodPicker()
                    }
                }
                else -> {}
            }
        }
    }
}
