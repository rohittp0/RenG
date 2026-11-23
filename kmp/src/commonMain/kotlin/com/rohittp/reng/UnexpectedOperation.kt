package com.rohittp.reng

import com.rohittp.reng.internal.lifecycle.RendererLifecycleOperation

internal fun unexpectedOperation(operation: RendererLifecycleOperation): Nothing =
    error("RenGRenderer's executor received an operation its caller did not request: $operation")
