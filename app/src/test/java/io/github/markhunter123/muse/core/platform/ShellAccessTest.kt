package io.github.markhunter123.muse.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellAccessTest {

    private fun probe(alive: Boolean = true, version: Int? = 13, granted: Boolean? = true, denied: Boolean = false,
                      paired: Boolean = true, lastFailure: String? = null) =
        ShellProbe(alive, version, granted, denied, paired, lastFailure)

    @Test fun `binder alive and granted is Ready`() =
        assertEquals(ShellState.Ready, ShellState.classify(probe(alive = true, version = 13, granted = true, paired = true)))

    @Test fun `paired but binder dead carries last failure`() {
        val s = ShellState.classify(probe(alive = false, version = null, granted = null, paired = true, lastFailure = "wadb port not found"))
        assertTrue(s is ShellState.PairedNotRunning && s.reason == "wadb port not found")
    }

    @Test fun `no key is NotPaired`() =
        assertEquals(ShellState.NotPaired, ShellState.classify(probe(alive = false, version = null, granted = null, paired = false)))

    @Test fun `running without grant is RunningNoPermission (official server coexistence)`() =
        assertEquals(ShellState.RunningNoPermission(denied = false), ShellState.classify(probe(alive = true, version = 13, granted = false, paired = true)))

    @Test fun `old server version is Unsupported`() =
        assertEquals(ShellState.Unsupported(10), ShellState.classify(probe(alive = true, version = 10, granted = null, paired = true)))

    /** The permission does not matter for a server too old to use. */
    @Test fun `unsupported regardless of grant`() =
        assertEquals(ShellState.Unsupported(10), ShellState.classify(probe(version = 10, granted = true)))

    @Test fun `a binder that stops answering mid-check reads as not running, never as too old or unauthorized`() {
        assertEquals(ShellState.PairedNotRunning(""), ShellState.classify(probe(version = null, granted = null, paired = true)))
        assertEquals(ShellState.NotPaired, ShellState.classify(probe(version = null, granted = null, paired = false)))
    }

    /** paired but nothing failed yet (e.g. right after boot): not running, no failure recorded. */
    @Test fun `paired without recorded failure has empty reason`() {
        val s = ShellState.classify(probe(alive = false, version = null, granted = null, paired = true, lastFailure = null))
        assertEquals(ShellState.PairedNotRunning(""), s)
    }

    @Test fun `every state says what to do next`() {
        val states = listOf(ShellState.NotPaired, ShellState.PairedNotRunning(""), ShellState.PairedNotRunning("wadb port not found"),
            ShellState.RunningNoPermission(false), ShellState.RunningNoPermission(true), ShellState.Ready, ShellState.Unsupported(10))
        assertEquals(states.size, states.map { it.hint }.toSet().size)
        assertTrue(ShellState.Unsupported(10).reason.contains("10"))
    }
}
