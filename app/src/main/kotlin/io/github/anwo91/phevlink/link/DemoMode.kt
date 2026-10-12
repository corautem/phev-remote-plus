package io.github.anwo91.phevlink.link

import io.github.anwo91.phevlink.core.obd.livetest.TestStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is running against a simulated car (an in-process [io.github.anwo91.phevlink.core.emulator.FakeCar])
 * and a simulated OBD adapter (an in-process [io.github.anwo91.phevlink.core.obd.emulator.FakeElm]).
 *
 * Deliberately in-memory only and **never persisted**: it resets to off on every cold start, so nobody can leave
 * the app in simulation and later mistake the fake car for their real one. A persistent EMULATION banner is shown
 * while it is on. Flip it through [CarRepository.setDemo] so the connection is rebuilt.
 */
object DemoMode {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /**
     * Live-test baselines and history while the demo runs. In memory and fresh for every demo, so simulated results
     * never become the real car's baselines (the "capacity dropped since last time" comparison, last-run verdicts).
     */
    @Volatile
    var testStore: TestStore = TestStore.Memory()
        private set

    internal fun set(on: Boolean) {
        if (on && !_active.value) testStore = TestStore.Memory()
        _active.value = on
    }
}
