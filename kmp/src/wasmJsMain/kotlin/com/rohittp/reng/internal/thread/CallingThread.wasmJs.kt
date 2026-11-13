package com.rohittp.reng.internal.thread

internal actual class CallingThread

private val browserCallingThread = CallingThread()

internal actual fun currentCallingThread(): CallingThread = browserCallingThread

internal actual fun CallingThread.isCurrentThread(): Boolean = this === browserCallingThread
