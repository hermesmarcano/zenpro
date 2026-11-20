# ZEN Xiaomi · Capa inteligente (Android / Xiaomi)

Una capa que vive **dentro de «ZEN Xiaomi»** (la variante personal de ZEN, con su propio nombre e identificador; ver
[Separación de la app normal](#separación-de-la-app-normal)) y observa el teléfono para tres cosas: *recordar* lo que importa
(texto de pantalla, notificaciones, audio), *cumplir* los límites y bloqueos que el propio usuario se pone, y
*proponer* respuestas que solo se envían si las apruebas. Está pensada para un Redmi Note 8 con MIUI 12.5.2
(Android 11) instalado por APK, **sin root y sin tocar el bootloader**.

> **Estado: compila, pasa lint y ~430 pruebas de la capa en JVM/Robolectric. No se ha probado en un teléfono
> real** (no hay dispositivo ni emulador en el entorno de desarrollo). Ver
> [Pendiente de validar en el teléfono](#pendiente-de-validar-en-el-teléfono).

## Qué es y qué no es

| Hace | No hace |
|---|---|
| Lee el texto de las apps permitidas (servicio de accesibilidad) y lo guarda en una memoria local con búsqueda | Leer contraseñas, bancos, billeteras, gestores de contraseñas, Ajustes ni la propia app |
| Guarda mensajes y avisos (sin códigos de un solo uso) y permite contestar con la acción «Responder» de la propia notificación | Enviar nada sin tu toque en «Enviar» |
| Cuenta el tiempo por app, avisa y, si lo pediste, bloquea (saca la app de primer plano) | Bloquear Ajustes, el teléfono o ZEN: el usuario siempre puede desactivar la capa |
| Escucha por el micrófono, con un aviso fijo, y manda los tramos de voz al *cerebro* para que los transcriba | Grabar llamadas (Android no deja a una app capturar el audio de la llamada, y grabar a otros tiene implicaciones legales) |
| Se reactiva sola si Xiaomi la mata (con un permiso opcional dado por ADB) | Funcionar como app de sistema: no es root, es una app normal con permisos que concede el usuario |

## Separación de la app normal

Esta rama (`claude/xiaomi-note8-layer`) es una **variante personal**: solo la usa su dueño, en su Redmi Note 8. No es la app
de ZEN que se publicará, y por eso están separadas de arriba abajo:

| | ZEN (normal, rama `main`) | ZEN Xiaomi (esta rama) |
|---|---|---|
| Nombre en el teléfono | ZEN | **ZEN Xiaomi** (icono con otro fondo) |
| Identificador de la app (`applicationId`) | `com.hermesmarcano.zen` | **`com.hermesmarcano.zen.xiaomi`** |
| Versión | `0.3.0` | `0.3.0-xiaomi` |
| CI | `android.yml` | `android-xiaomi.yml` (el normal ignora esta rama) |
| Descarga | pre-release `android-latest` | pre-release **`xiaomi-latest`** |
| Accesibilidad, micrófono, lectura de notificaciones | **nunca** | sí (la capa) |

- **Se instalan una al lado de la otra**: son apps distintas para Android, con datos, permisos y ajustes independientes. Instalar
  ZEN Xiaomi no sustituye ni toca a ZEN.
- **Cuidado con la alarma**: si ambas tienen alarma programada, sonarán las dos. Usa la alarma en una sola.
- El CI de la variante **comprueba el identificador del APK** y falla si por error vuelve a ser el de la app normal.
- **La rama no se fusiona con `main` ni se abre PR hacia otra rama**: lo que haya de común se mejora en `main` y se trae a esta
  rama, nunca al revés. La regla completa está en `CLAUDE.md`.
- La firma (`zen-sideload.jks`) es la misma que usa el APK normal para sideload; Android solo distingue las apps por su
  identificador, así que no chocan.

## Principios de diseño

1. **Todo apagado por defecto**, cada fuente por separado, con una **pausa** global siempre a mano.
2. **Visible**: el micrófono tiene un aviso fijo mientras escucha (Android 11 no trae el punto verde de privacidad).
3. **Privacidad antes de guardar**: se quitan códigos de un solo uso, tarjetas (Luhn), cuentas de 18 dígitos,
   contraseñas etiquetadas y claves (`sk-…`, `ghp_…`, JWT…) *antes* de escribir en disco o enviar. El título y el
   cuerpo de una notificación se revisan **juntos**: un «Código de verificación» en el título oculta el número del cuerpo.
4. **El cerebro puede restringir, nunca relajar**: puede proponer una respuesta, bloquear una app (máx. 24 h),
   pausar la observación o avisar. No puede quitar un bloqueo ni enviar un mensaje. Y aun así está acotado: **solo bloquea apps
   que tú ya pusiste bajo una regla que bloquea en «Límites»** (un límite que bloquea al llegar, o una franja prohibida; una regla que
   solo avisa no vale, y sin regla no toca la app), con un máximo de 3 bloqueos, 6 avisos y 10 borradores por hora.
5. **Los compromisos tienen fricción**: endurecer un límite vale al instante; **aflojarlo o quitarlo tarda 24 h**.
   Soltar un bloqueo exige esperar un minuto y escribir un motivo, solo da un *pase* de 15 minutos y máximo 3 al día.
6. **Mínimo privilegio**: no pide SMS, contactos, ubicación, llamadas, almacenamiento, superposición ni
   `QUERY_ALL_PACKAGES` (hay una prueba que lo veta). La accesibilidad no declara gestos ni filtro de teclas.

## Instalación en el Redmi Note 8 (MIUI 12.5.2 global)

Los nombres exactos de los menús cambian entre versiones de MIUI; si alguno no coincide, busca el ajuste por nombre.

1. **Instala el APK** de la pre-release **`xiaomi-latest`** (archivo `zen-xiaomi.apk`; ver también [`ANDROID.md`](ANDROID.md)). MIUI avisará de «instalar apps desconocidas» y puede mostrar un
   análisis de seguridad: es normal en un APK fuera de Play.

   **Si sale «O app foi bloqueado para proteger seu dispositivo» / «App bloqueada para proteger tu dispositivo» (Google Play Protect):**
   es la *protección mejorada contra fraude* de Play Protect. Bloquea la instalación de apps que llegan desde un navegador, WhatsApp o
   un gestor de archivos y piden permisos sensibles (accesibilidad, acceso a notificaciones, SMS), y esta app pide los dos primeros porque
   para eso existe. Google la tiene activa en algunos países (entre ellos Brasil, India, Singapur, Tailandia, Filipinas, Kenia, Nigeria,
   Sudáfrica, Vietnam y Hong Kong) y no depende de la app: pasaría con cualquier APK que declare esos servicios. Dos salidas:
   - **Apagar el análisis un rato:** Google Play → tu foto de perfil → **Play Protect** → engranaje ⚙ → desactiva el interruptor de analizar/verificar
     apps (en portugués, «Verificar apps com o Play Protect») → instala el APK → **vuelve a activarlo**. Si después Play Protect avisa de la
     app, elige conservarla («Manter»).
   - **Instalar por cable con `adb`** (no es una descarga de internet): con *Depuración USB* e *Instalar vía USB* activados en Opciones de
     desarrollador, `adb install -r zen-xiaomi.apk`. Es lo más probable que funcione sin tocar Play Protect, pero no está confirmado por Google.
   Con Android 13 o superior, además, hay que abrir *Información de la app → ⋮ → Permitir ajustes restringidos* antes de poder activar la
   accesibilidad y el acceso a notificaciones (el Redmi Note 8 con Android 11 no lo necesita).
2. Abre **ZEN Xiaomi** → **Ajustes → Abrir la capa**. Ahí, arriba, la sección **Estado** te dice qué falta y tiene un botón para cada cosa.
3. **Accesibilidad** (para leer la pantalla y bloquear): Ajustes del teléfono → *Ajustes adicionales → Accesibilidad →
   Servicios descargados → «ZEN Xiaomi · capa»* → activar. MIUI mostrará un aviso fuerte: lee la descripción, es el texto exacto de lo que hace.
4. **Acceso a notificaciones**: Ajustes → *Notificaciones y barra de estado → Acceso a notificaciones → ZEN Xiaomi* → activar.
5. **Micrófono** (opcional, necesita un cerebro configurado): el interruptor pide el permiso.
6. **Que Xiaomi no la mate** (imprescindible, es lo que más rompe estas apps):
   - *Seguridad → Permisos → Inicio automático* → activa **ZEN Xiaomi**.
   - *Ajustes → Batería → Ahorro de batería de las apps → ZEN Xiaomi → **Sin restricciones*** (la pantalla Capa tiene un botón).
   - En *Recientes*, **bloquea ZEN Xiaomi** (mantén pulsada su tarjeta → candado) para que «limpiar memoria» no la cierre.
   - Márcalo como hecho en el Estado de la capa («Ya lo hice»).
7. **Reparación automática (opcional)**: Android no deja que una app se dé a sí misma `WRITE_SECURE_SETTINGS`, pero tú puedes
   concedérselo **una vez** por ADB. Con él, si Xiaomi apaga el servicio de accesibilidad, la capa lo reactiva sola.
   En el teléfono: *Opciones de desarrollador → Depuración USB* y **«Depuración USB (ajustes de seguridad)»** (MIUI pide
   cuenta Mi y SIM para esto). Conecta el cable y, desde un ordenador con `adb`:

   ```
   adb shell pm grant com.hermesmarcano.zen.xiaomi android.permission.WRITE_SECURE_SETTINGS
   ```

   El identificador es el de la variante (`.xiaomi`), no el de ZEN normal. La pantalla Capa muestra este comando con un botón para copiarlo. Sin el permiso la capa solo avisa de que el servicio se cayó.

## El cerebro (protocolo)

El *cerebro* es tu servidor (por ejemplo, un agente Hermes). La capa le habla con tres endpoints. El lado del servidor es el
**adaptador `zen-brain`** de este repositorio ([`brain/`](../brain/README.md)): traduce este protocolo al de Hermes y es el único
que valida lo que el modelo propone.

- Dirección: `https://…` desde cualquier sitio; `http://` **solo** cuando el tráfico no sale en claro de todos modos: este mismo
  teléfono (`localhost`, `127.x`) o Tailscale (`100.64–127.x`, `*.ts.net`, `fd7a:115c:a1e0::/48`; WireGuard ya lo cifra). Una red
  local (`192.168.x`, `10.x`, `*.local`) **no** vale: en una wifi cualquiera podrían leer el token. Sin usuario/contraseña en la URL.
- Autenticación: cabecera `Authorization: Bearer <token>`. El token nunca va en la URL. No se siguen redirecciones.
- `User-Agent: ZEN-Layer/<versión>`. Cada teléfono manda un `device` aleatorio (UUID) que se crea al guardar el cerebro.

### `POST /v1/events`

Cuerpo (`application/json`), hasta 100 eventos por lote:

```json
{"device": "6f1c…", "events": [
  {"id": "…", "kind": "notification", "package": "com.whatsapp", "title": "Marta",
   "text": "¿cenamos hoy?", "at": 1763500000000, "extra": {"conv": "com.whatsapp|0|…"}}
]}
```

`kind` ∈ `screen`, `notification`, `audio`, `usage`, `system`, `override`. `at` es epoch en milisegundos. `extra.conv` identifica
la conversación cuando se puede responder. Un `2xx` confirma el lote. `401/403` = token rechazado (la capa se detiene hasta que se
arregle). Otro `4xx` = rechazado (se reenvía uno a uno para aislar el malo; con 5 rechazos de un mismo elemento se descarta).
`408/425/429/5xx` o sin red = se reintenta con espera creciente (30 s → 1 h) y **no se pierde nada** durante 24 horas.

### `POST /v1/audio`

Cuerpo `audio/wav` (16 kHz, 16 bits, mono, **como mucho 29 s**: el servidor rechaza más de 30) de un tramo de voz. Cabeceras:
`X-Zen-Segment-Id`, `X-Zen-Started-At` (epoch ms), `X-Zen-Duration-Ms`. El cerebro transcribe; la capa **no** transcribe en el teléfono.

### `GET /v1/commands?device=<id>&cursor=<cursor>`

```json
{"cursor": "42", "commands": [
  {"id": "c1", "type": "draft_reply", "conversation": "com.whatsapp|0|…", "text": "Claro, a las 9"},
  {"id": "c2", "type": "block", "package": "com.zhiliaoapp.musically", "minutes": 60},
  {"id": "c3", "type": "notify", "title": "ZEN", "text": "Llevas 1 h en TikTok"},
  {"id": "c4", "type": "pause", "minutes": 30}
]}
```

- `id` es obligatorio y se usa para **no ejecutar dos veces** la misma orden (la capa recuerda los últimos 500).
- `draft_reply` crea un **borrador pendiente** (notificación con «Enviar / Descartar»); nunca envía.
- `block`: 1–1440 minutos; se ignora para apps protegidas **y para toda app que no tenga una regla que bloquee en «Límites»**. `pause`: 1–1440
  minutos. `notify`: título de hasta 60 y texto de hasta 200 caracteres, **sin enlaces** (esquemas, `www.`, dominios, IP: se rechaza).
- Límites de la propia app, aunque el servidor falle: 6 avisos, 3 bloqueos y 10 borradores del cerebro por hora.
- Un tipo desconocido se salta (el cerebro puede ser más nuevo que el teléfono). Un `404` significa «este cerebro no manda órdenes»: es válido.

## Qué se guarda, dónde y cuánto

- **En el teléfono**, en el almacenamiento privado de la app (`allowBackup=false`): estado (`layer/layer.json`), memoria
  (`zen-layer.db`, SQLite con búsqueda FTS4 sin tildes ni mayúsculas) y audio pendiente (`layer/audio/`).
- **Qué va cifrado**: con una clave del **Keystore de Android** (AES-256-GCM, respaldada por hardware en el Redmi Note 8, que no
  sale del teléfono) se cifran **el token del cerebro, el texto de los eventos en cola y el audio pendiente**. Si el Keystore falla
  no se guarda nada en claro: el cambio falla y la pantalla lo dice. Un token guardado en claro por una versión anterior se
  vuelve a escribir cifrado al abrir la capa.
- **Un fallo pasajero no borra nada**: si el Keystore no responde *ahora* (distinto de «la clave ya no existe»), el token cifrado se
  conserva en el archivo, el audio y los eventos en cola se quedan esperando y se reintenta; solo lo irrecuperable de verdad
  (manipulado, o clave perdida) se descarta. Si el token no se puede abrir, la pantalla lo dice («vuelve a escribirlo»).
- **Qué NO va cifrado, y por qué**: la **memoria** (`zen-layer.db`). Necesita búsqueda de texto completo, y cifrar el contenido
  la haría imposible (haría falta SQLCipher). Está protegida por el aislamiento de Android y por el cifrado de disco del teléfono,
  y se limita a 30 días. Por eso la cola cifrada protege el audio (que no existe en ningún otro sitio) y el token, pero no
  esconde del todo el *texto*, que también está en la memoria: no lo vendas como más de lo que es.
- **Retención**: memoria 30 días o 20 000 filas; cola de envío **24 horas o 500 elementos**; audio hasta que se envía (tope de 200 MB).
  Con la capa en pausa no sale nada, así que lo que espera se retiene hasta 24 h como mucho y luego se descarta.
- **Borrar**: «Borrar todo…» en la pantalla Capa (memoria + cola + audio); vetar una app también olvida lo que había de ella.
  «Quitar cerebro» borra además la cola de envío y el audio pendiente: eran para ese cerebro y no deben acabar en otro servidor
  que se configure después (la memoria local se conserva).
- **Fuera del teléfono**: nada, salvo lo que mandes al cerebro que tú configures.

## Pendiente de validar en el teléfono

Nada de esto se ha podido probar sin un dispositivo. Las pruebas cubren la lógica y el cableado de Android (Robolectric), no el
comportamiento real de MIUI:

- **Rendimiento del Keystore**: que cifrar un tramo de voz de 29 s (~1 MB) con la clave del Keystore del Note 8 (Keymaster 4) sea
  razonable. Si fuera lento, habría que pasar a una clave de datos en memoria envuelta por el Keystore.
- **Tailscale detrás de un CGNAT**: una dirección `100.64–127.x` sin Tailscale activo (p. ej. la red CGNAT de un operador) iría en
  claro por `http://`; la app no puede distinguirlo. Con `https://` no pasa.
- Que el servicio de accesibilidad **sobreviva** a la limpieza de memoria y a los reinicios de MIUI con los ajustes del paso 6, y que
  `WRITE_SECURE_SETTINGS` por ADB funcione en MIUI para reactivarlo.
- Que `FLAG_SECURE`, campos de contraseña y apps con vistas «no importantes» se comporten como se espera al leer la pantalla.
- **Micrófono**: que el servicio con micrófono en primer plano arranque y aguante en Android 11/MIUI (desde segundo plano no puede
  reiniciarse: hay que abrir ZEN, y se avisa), la calidad de audio con el teléfono en el bolsillo, y el umbral del detector de voz.
  Un ruido fuerte y continuo (coche, ventilador) puede generar tramos largos de ruido: el cerebro debe descartarlos.
- **Responder desde la notificación**: depende de que WhatsApp (u otra app) exponga la acción «Responder» en esa notificación;
  si el usuario la descarta, esa conversación deja de ser contestable. WhatsApp puede considerar automatización lo que imite a un
  cliente; el diseño (borrador + aprobación humana, volumen bajo) es la variante de menor riesgo, no una garantía.
- Batería y calor con el micrófono y la accesibilidad activos en un equipo de 2019.
- Que el bloqueo (pulsar «Inicio» al abrir una app bloqueada) no entre en bucles con ninguna app o launcher concreto.
- `JobScheduler` en MIUI (los trabajos periódicos pueden retrasarse mucho con el ahorro de batería activo).

## Limitaciones por diseño (y por Android)

- **Android 17 con «Protección avanzada»** deshabilita la accesibilidad a apps que no son herramientas de accesibilidad: en un teléfono
  moderno con ese modo la capa de pantalla no funcionaría. En Android 11 (tu Redmi) no existe.
- La capa **no es un límite de seguridad**: es una ayuda de compromiso. Quien tenga el teléfono puede desactivar el servicio en Ajustes
  (por eso Ajustes nunca se bloquea); la fricción está en la propia app, no en el sistema.
- Play Store: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS y la accesibilidad con este fin no cumplen sus políticas. Es solo para instalar por APK.

## Para desarrollar

Código en `android/app/src/main/java/com/hermesmarcano/zen/layer/`:

- `core/` — lógica pura sin `android.*` (privacidad, política de captura, límites, VAD, cola, borradores, órdenes, reparación).
- `data/` — estado en JSON atómico, SQLite (línea de tiempo FTS4 + cola) y archivos de audio.
- `link/` — protocolo, cliente HTTP y motor de sincronización (se prueba contra un servidor HTTP real en `localhost`).
- `capture/` — adaptadores finos de Android (accesibilidad, notificaciones, micrófono, trabajos, avisos).
- `ui/` — la pantalla «Capa» (los editores son paneles dentro de la pantalla, no diálogos: un campo de texto dentro de un diálogo
  impide que Compose llegue a reposo en Robolectric).

```bash
cd android
export ANDROID_HOME=/ruta/al/android-sdk
./gradlew testDebugUnitTest --tests 'com.hermesmarcano.zen.layer.*'   # solo la capa
./gradlew testDebugUnitTest lintDebug assembleRelease                  # todo, como pide CLAUDE.md
```
