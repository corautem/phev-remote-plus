package io.github.anwo91.phevlink.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is running against a simulated car (an in-process [io.github.anwo91.phevlink.core.emulator.FakeCar]).
 *
 * Deliberately in-memory only and **never persisted**: it resets to off on every cold start, so nobody can leave
 * the app in simulation and later mistake the fake car for their real one. A persistent EMULATION banner is shown
 * while it is on. Flip it through [CarRepository.setDemo] so the connection is rebuilt.
 */
object DemoMode {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    internal fun set(on: Boolean) {
        _active.value = on
    }
}
