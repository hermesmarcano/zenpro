# ZEN — Arquitectura técnica (MVP)

Este documento traduce el diseño UX (ver el canvas de mockups y `docs/UX-SPEC.md`)
a decisiones técnicas concretas de iOS. Todo lo marcado **[VERIFICAR]** depende de
una API muy nueva (AlarmKit, iOS 26) que no se puede probar en este entorno sin
Xcode — queda como primera tarea del ciclo de revisión en cuanto haya acceso a un
build real.

## 1. Targets del proyecto Xcode

| Target | Tipo | Responsabilidad |
|---|---|---|
| `ZEN` | App (SwiftUI) | UI completa, lógica de negocio, persistencia |
| `ZENDeviceActivityMonitor` | App Extension (Device Activity Monitor) | Aplica/quita el shield automáticamente al entrar/salir de una ventana de horario, incluso con la app cerrada |
| `ZENShieldConfiguration` | App Extension (Shield Configuration) | Personaliza título/subtítulo/botones de la pantalla nativa de bloqueo |
| `ZENShieldAction` | App Extension (Shield Action) | Maneja el tap en los botones del shield (abrir ZEN) |

Todos comparten un **App Group** (`group.<bundleID>.zen`) para el estado mínimo que
necesitan las extensiones (reglas de horario/ubicación resueltas, no la selección de
apps en sí — esa la gestiona `ManagedSettingsStore` directamente y permanece opaca).

Widget de Lock Screen con el MIT: **fuera del MVP**, se evalúa en v1.1.

## 2. Frameworks de Apple por feature

- **Alarma + QR**: AlarmKit (iOS 26+) para el disparo garantizado a nivel de OS
  (salta DND, funciona con la app cerrada) **[VERIFICAR]**; AVFoundation
  (`AVCaptureMetadataOutput`, no Vision — más simple y es el estándar para
  escaneo de QR en tiempo real) para escanear; CoreImage para generar el QR.
- **Bloqueo de apps**: `FamilyControls` (autorización + picker), `ManagedSettings`
  (aplicar el shield), `DeviceActivity` (automatizar por horario vía la extensión).
- **Ubicación**: `CoreLocation`, geofencing con `CLCircularRegion` (máx. ~20 regiones
  monitoreadas por app — limita el número de "lugares" configurables).
- **Persistencia**: `SwiftData` para todo el modelo local (sin backend en el MVP).
- **Notificaciones**: `UserNotifications` para prime de permisos y recordatorios.

## 3. Modelo de datos (SwiftData, resumen)

```swift
@Model final class AlarmConfig {
    var id: UUID
    var time: DateComponents          // hora/minuto
    var repeatDays: Set<Weekday>
    var isEnabled: Bool
    var soundName: String
    var escalationEnabled: Bool
    var anchorID: UUID                // -> QRAnchor
    var graceSeconds: Int             // duración de "necesito 2 minutos"
}

@Model final class QRAnchor {
    var id: UUID
    var label: String                 // "Espejo del baño"
    var secretToken: String           // aleatorio, generado on-device, nunca sale del dispositivo
    var backupCode: String            // código numérico de respaldo (ver edge case §5)
    var confirmedAt: Date?
}

@Model final class BlockRuleSet {
    var id: UUID
    var selectionData: Data           // FamilyActivitySelection codificada — opaca
    var scheduleWindows: [ScheduleWindow]
    var locationRules: [LocationRule]
}

struct ScheduleWindow: Codable {
    var start: DateComponents
    var end: DateComponents
    var days: Set<Weekday>
    var isUnlockWindow: Bool
}

@Model final class LocationRule {
    var id: UUID
    var label: String
    var latitude: Double
    var longitude: Double
    var radiusMeters: Double
    var isUnlockZone: Bool
}

@Model final class TaskItem {
    var id: UUID
    var text: String
    var isDone: Bool
    var isMIT: Bool
    var dayKey: String                // bucket de día local, ej. "2026-08-24"
}

@Model final class UnlockRequest {    // auditoría del flujo de fricción
    var id: UUID
    var requestedAt: Date
    var reason: String
    var grantedAt: Date?
}
```

## 4. Flujos críticos

### 4.1 Alarma → QR → MIT
1. `AlarmManager` (AlarmKit) programa la alarma con `secondaryButtonBehavior`
   apuntando a abrir la app en vez de detener directamente **[VERIFICAR API exacta]**
   — el objetivo es que ningún botón del sistema apague la alarma sin pasar por
   ZEN.
2. Al abrir, se presenta la vista de sonando a pantalla completa con
   `AVCaptureSession` embebido.
3. El QR escaneado se valida localmente: `anchorID` + `secretToken` deben coincidir
   con un `QRAnchor` guardado. Sin red, sin backend.
4. Éxito → `AlarmManager.stop(id:)` + log de dismissal + navegación a la pantalla de
   MIT.

### 4.2 Bloqueo automático por horario
`DeviceActivityCenter.startMonitoring` registra los `ScheduleWindow` como
`DeviceActivitySchedule`. La extensión `ZENDeviceActivityMonitor` recibe
`intervalDidStart`/`intervalDidEnd` y aplica/retira `ManagedSettingsStore().shield.applications`
directamente — funciona aunque la app principal esté cerrada.

### 4.3 Desbloqueo por ubicación
`CLLocationManager` con monitoreo de región en la app principal. Al entrar a una
`LocationRule` marcada como zona de desbloqueo, se recalcula el estado combinado
(horario ∧ ubicación) y se reaplica el shield completo — nunca un toggle parcial,
para evitar condiciones de carrera entre el trigger de horario (extensión) y el de
ubicación (app principal). Fuente de verdad única: un `BlockingStateCoordinator` que
escribe el estado resuelto al App Group.

### 4.4 Fricción de desbloqueo anticipado
Cooldown persistido en App Group (sobrevive el cierre de la app). Al cumplirse +
justificación escrita, se levanta el shield por una ventana de gracia (ej. 30 min) y
se reprograma su reaplicación automática al vencer.

## 5. Edge cases técnicos (más allá de los de UX)

| Caso | Resolución |
|---|---|
| App matada por el sistema cuando debe sonar la alarma | AlarmKit dispara a nivel OS, independiente del proceso de la app (como el Reloj nativo) |
| Extensión DeviceActivityMonitor tiene límite de memoria estricto y sin red | Mantenerla trivial: solo llamadas a `ManagedSettingsStore`, sin lógica pesada |
| Usuario desactiva Screen Time desde Ajustes (fuera de ZEN) | Chequear `AuthorizationCenter.shared.authorizationStatus` en cada foreground; mostrar banner honesto, nunca fingir que el bloqueo sigue activo |
| Límite de ~20 regiones geofenced por app | Tope de reglas de ubicación configurables en el MVP (ej. máx. 8) |
| Permiso de ubicación baja de "Siempre" a "Mientras se usa" | El geofencing deja de funcionar en background; detectar y pedir re-autorización |
| Cámara denegada específicamente (distinto de otros permisos) | **Código de respaldo numérico** impreso junto al QR como vía de emergencia — si no existe esto, un fallo de cámara deja al usuario sin forma de apagar la alarma. Es un edge case de seguridad, no cosmético |
| Cambio de zona horaria / DST | `DeviceActivitySchedule` maneja DST en horario local automáticamente (framework de Apple); el repeat de la alarma debe recalcularse en `significantTimeChangeNotification` |
| Revisión de App Store para apps de Screen Time | Solicitar la entitlement `com.apple.developer.family-controls` apenas exista la cuenta Developer (puede tardar días/semanas en aprobarse) — bloqueante para TestFlight/App Store, no para desarrollo local |
| Foto del QR tomada por otra persona / capturada de antemano | Aceptado como limitación conocida (igual que apps como Alarmy) — el objetivo es romper el hábito de un toque, no defender contra un atacante sofisticado; no vale la pena la complejidad de rotar el token |
| Se borra un `QRAnchor` que una `AlarmConfig` sigue referenciando | Hoy no hay UI para borrar anclas, así que es teórico. Cuando se construya esa UI (M5/M6), debe bloquear o advertir el borrado si hay alarmas dependientes — nunca dejar que `AlarmRingingView` se quede sin ancla en runtime, porque ese estado no debe tener un botón de salida (sería una puerta trasera para apagar la alarma sin QR) |

## 6. Hoja de ruta por milestones

Cada milestone se ejecuta bajo el ciclo de planificación/código/revisión definido en
`CLAUDE.md` — no se avanza al siguiente con hallazgos abiertos.

- **M0 — Fundaciones**: cuenta Apple Developer, solicitud de entitlement Family
  Controls, scaffold del proyecto Xcode (targets + App Group), pipeline CI
  (GitHub Actions/Xcode Cloud) con build+test en cada push, modelos SwiftData.
- **M1 — Ancla QR**: generación del QR, flujo de confirmación de ubicación, escaneo
  y validación local, código de respaldo.
- **M2 — Alarma**: creación/edición, integración AlarmKit, pantalla de sonando,
  dismissal vía QR, handoff a MIT. *(El núcleo del producto — máxima prioridad de
  revisión.)*
- **M3 — Bloqueo de apps**: picker nativo, reglas de horario, extensión
  DeviceActivityMonitor, personalización del Shield.
- **M4 — Ubicación**: geofencing, reglas de lugar, fricción de desbloqueo anticipado.
- **M5 — Tareas/MIT + Home**: lista de tareas, dashboard.
- **M6 — Endurecimiento**: recorrido completo de edge cases de este documento, beta
  en TestFlight, preparación de envío a App Store.
