package com.unifiedmesh.core.model

/**
 * Wall-clock abstraction.
 *
 * The bridge's duplicate cache and TTL logic are time-dependent, so time is
 * injected rather than read from `System.currentTimeMillis()`. Tests advance a
 * fake clock instead of sleeping.
 */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
