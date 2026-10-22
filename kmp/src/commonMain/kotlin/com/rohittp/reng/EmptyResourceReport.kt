package com.rohittp.reng

internal fun emptyResourceReport(): ResourceReport = ResourceReport(
    entries = emptyList(),
    totals = ResourceUsage(rawBytes = 0L, decodedCpuBytes = 0L, knownGpuBytes = 0L, hasUnknownGpuBytes = false),
    // Zeroes throughout, including the cumulative eviction counters. This is the same "not
    // answering questions" the empty entry list already means, not a claim that nothing was ever
    // evicted -- ADR 0048.
    cpuResidency = ResourceResidency(
        residentBytes = 0L,
        budgetBytes = 0L,
        evictedKeyCount = 0L,
        evictedBytes = 0L,
    ),
)
