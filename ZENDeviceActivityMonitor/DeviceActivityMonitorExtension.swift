import DeviceActivity

/// Aplica/retira el shield al iniciar o terminar una ventana de horario.
///
/// M0: stub válido y compilable (comportamiento por defecto, sin overrides).
/// La lógica de resolución de estado combinado horario ∧ ubicación —incluyendo
/// el mapeo de `DeviceActivityName` a `ScheduleWindow` y la lectura del App
/// Group compartido con la app principal— se implementa en M3/M4 (ver
/// ARCHITECTURE.md §4.2–4.3), una vez esté diseñado el `BlockingStateCoordinator`.
final class DeviceActivityMonitorExtension: DeviceActivityMonitor {
}
