package com.guardian.safety.ui

/**
 * Compact "how long ago" description used across the live, family and guardian
 * screens. Values always come from real timestamps the app measured or received.
 */
internal fun relativeTime(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 1_440 -> "${minutes / 60}h ago"
        else -> "${minutes / 1_440}d ago"
    }
}
