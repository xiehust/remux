package dev.remux.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.remux.app.ui.screens.DevicePickerScreen
import dev.remux.app.ui.screens.HostEditScreen
import dev.remux.app.ui.screens.HostListScreen
import dev.remux.app.ui.screens.TerminalScreen

/** App navigation routes. A lightweight state machine keeps the dependency
 *  surface small and the build deterministic. */
sealed interface Route {
    data object HostList : Route
    data class HostEdit(val hostId: Long?) : Route
    data object DevicePicker : Route
    data class Terminal(val hostId: Long) : Route
}

@Composable
fun RemuxApp(container: AppContainer) {
    var route by remember { mutableStateOf<Route>(Route.HostList) }

    when (val r = route) {
        is Route.HostList -> HostListScreen(
            container = container,
            onAddHost = { route = Route.HostEdit(null) },
            onConnect = { host -> route = Route.Terminal(host.id) },
            onEditHost = { host -> route = Route.HostEdit(host.id) },
        )

        is Route.HostEdit -> HostEditScreen(
            container = container,
            hostId = r.hostId,
            onDone = { route = Route.HostList },
        )

        is Route.DevicePicker -> DevicePickerScreen(
            container = container,
            onPicked = { route = Route.HostList },
        )

        is Route.Terminal -> TerminalScreen(
            container = container,
            hostId = r.hostId,
            onBack = { route = Route.HostList },
        )
    }
}
