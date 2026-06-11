/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class FcitxDispatcher(private val controller: FcitxController) : CoroutineDispatcher() {

    class WrappedRunnable(private val runnable: Runnable) : Runnable by runnable {
        private val time = System.currentTimeMillis()

        override fun run() {
            val delta = System.currentTimeMillis() - time
            if (delta > JOB_WAITING_LIMIT) {
                Timber.w("$this has waited $delta ms to get run since created!")
            }
            runnable.run()
        }

        override fun toString(): String = "WrappedRunnable[${hashCode()}]"
    }

    // this is fcitx main thread
    private val internalDispatcher = Executors.newSingleThreadExecutor {
        Thread(it).apply {
            name = "fcitx-main"
        }
    }.asCoroutineDispatcher()

    private val internalScope = CoroutineScope(internalDispatcher)

    interface FcitxController {
        fun nativeStartup()
        fun nativeLoopOnce()
        fun nativeScheduleEmpty()
        fun nativeExit()
    }

    private val runningLock = Mutex()

    private val queue = ConcurrentLinkedQueue<WrappedRunnable>()

    private val isRunning = AtomicBoolean(false)

    /**
     * Start the dispatcher
     * This function returns immediately
     */
    fun start() {
        Timber.d("[FcitxDispatcher] start: launching fcitx-main coroutine")
        internalScope.launch {
            runningLock.withLock {
                if (isRunning.compareAndSet(false, true)) {
                    Timber.i("[FcitxDispatcher] start: isRunning=true, calling nativeStartup")
                    controller.nativeStartup()
                    Timber.i("[FcitxDispatcher] start: nativeStartup returned, entering event loop")
                    var loopCount = 0L
                    while (isActive && isRunning.get()) {
                        // blocking...
                        controller.nativeLoopOnce()
                        // do scheduled jobs
                        var jobCount = 0
                        while (true) {
                            val block = queue.poll() ?: break
                            block.run()
                            jobCount++
                        }
                        loopCount++
                        if (loopCount % 500L == 0L) {
                            Timber.v("[FcitxDispatcher] eventLoop: loopCount=$loopCount, queueSize=${queue.size}")
                        }
                    }
                    Timber.i("[FcitxDispatcher] start: event loop exited (isActive=$isActive, isRunning=${isRunning.get()}), calling nativeExit")
                    controller.nativeExit()
                    Timber.i("[FcitxDispatcher] start: nativeExit done")
                } else {
                    Timber.w("[FcitxDispatcher] start: already running, ignored")
                }
            }
        }
    }

    /**
     * Stop the dispatcher
     * This function blocks until fully stopped
     */
    fun stop(): List<Runnable> {
        Timber.i("[FcitxDispatcher] stop: isRunning=${isRunning.get()}, queueSize=${queue.size}")
        return if (isRunning.compareAndSet(true, false)) {
            runBlocking {
                Timber.d("[FcitxDispatcher] stop: nativeScheduleEmpty to unblock loopOnce")
                controller.nativeScheduleEmpty()
                runningLock.withLock {
                    val rest = queue.toList()
                    queue.clear()
                    Timber.i("[FcitxDispatcher] stop: cleared ${rest.size} pending jobs from queue")
                    rest
                }
            }
        } else {
            Timber.w("[FcitxDispatcher] stop: was not running, nothing to do")
            emptyList()
        }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (!isRunning.get()) {
            throw IllegalStateException("Dispatcher is not in running state!")
        }
        queue.offer(WrappedRunnable(block))
        Timber.v("[FcitxDispatcher] dispatch: job enqueued, queueSize=${queue.size}")
        // always call `nativeScheduleEmpty()` to prevent `nativeLoopOnce()` from blocking
        // the thread when we have something to run
        controller.nativeScheduleEmpty()
    }

    companion object {
        const val JOB_WAITING_LIMIT = 2000L
    }

}