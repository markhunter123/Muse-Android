package io.github.markhunter123.muse.core.platform

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

object AppVisibility {
    val isForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
