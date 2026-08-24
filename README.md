# ZEN

App de iOS que te obliga a levantarte de la cama (alarma + QR físico) y protege tu
enfoque (bloqueo duro de redes sociales configurable por horario/ubicación), con una
lista de tareas centrada en una única Tarea Más Importante (MIT) por día.

Ver `CLAUDE.md` para las reglas del proyecto y `docs/ARCHITECTURE.md` para el diseño
técnico completo.

## Desarrollo

El proyecto usa [XcodeGen](https://github.com/yonaskolb/XcodeGen): el `.xcodeproj`
**no se commitea**, se genera a partir de `project.yml`.

```bash
brew install xcodegen
xcodegen generate
open ZEN.xcodeproj
```

CI (`.github/workflows/ios.yml`) genera el proyecto y corre build + tests en cada
push, en un runner macOS — es la validación de compilación real del proyecto,
necesaria porque el desarrollo de esta app ocurre sin Xcode local (ver `CLAUDE.md`).
