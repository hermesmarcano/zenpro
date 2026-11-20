# zen-brain — adaptador entre «ZEN Xiaomi» y Hermes

Servicio pequeño (solo biblioteca estándar de Python) que va **entre el teléfono y Hermes**. El teléfono nunca habla con
Hermes: habla con este adaptador, y el adaptador es **el único que valida** lo que el modelo propone antes de que llegue al teléfono.

```
Teléfono (ZEN Xiaomi) ──TLS / tailnet──▶ tailscale serve ──▶ adaptador 127.0.0.1:8787 (usuario zen)
                                                                │
                                                                ├──▶ Hermes «zen-brain»  127.0.0.1:8642  (solo loopback)
                                                                ├──▶ faster-whisper (en memoria, nunca a disco)
                                                                └──▶ audit.jsonl (solo se añade, 600) + state.db (600)
```

Protocolo con el teléfono: [`docs/XIAOMI-LAYER.md`](../docs/XIAOMI-LAYER.md). Este adaptador lo traduce al formato interno del spec
(`event_id`, `kind: notification|screen|transcript`, `app`, `sender`, `text`) y devuelve órdenes en el formato del teléfono.

## Qué garantiza

| Garantía | Cómo |
|---|---|
| Solo entra quien tiene un token | `Authorization: Bearer`, un token **por dispositivo**, guardado como hash SHA-256; comparación en tiempo constante; 401 idéntico para ausente/corto/incorrecto; nunca en la URL |
| Rotar o revocar sin reiniciar | `tokens.json` se relee al cambiar (≤ 1 s). Si se corrompe, **falla cerrado** (nadie entra) y se avisa en la auditoría |
| El texto observado no son instrucciones | Va solo en el mensaje de usuario, dentro de `<EVENTO id="…">` con **identificador aleatorio por petición**; se quitan controles, bidireccionales, caracteres «etiqueta» y selectores de variación; se aplica NFKC; `<`/`>` y lookalikes desaparecen; los marcadores `System:`, `[INST]`, ```` ``` ```` pierden su forma |
| La salida del modelo no es fiable | `json.loads` estricto (sin claves repetidas, sin NaN), `{"commands": [...]}`, máx. 3, **esquema estricto** por orden (campos exactos, tipos exactos, sin decimales), `reasoning_content` ignorado siempre |
| El modelo no elige a quién se responde | `draft_reply` va a la conversación **del propio evento** (la fija el adaptador) y solo para notificaciones; nunca se envía solo (la persona lo aprueba en el teléfono) |
| El modelo no elige el título de un aviso | Siempre «ZEN»; ≤ 200 caracteres y **sin enlaces** |
| Bloqueos acotados | Solo apps de `ZEN_BLOCK_ALLOWLIST` (vacía por defecto) y nunca protegidas; clamp a `ZEN_BLOCK_MAX_MINUTES`; 3 por hora; **tope de minutos por día**. El teléfono además solo bloquea apps con una regla que bloquea en «Límites» |
| Pausas acotadas | Clamp, 2 por hora y **tope de minutos por día** (varias órdenes no se encadenan en una pausa permanente) |
| Cortacircuitos | Respuestas no-JSON en ráfaga, bloqueos o pausas en ráfaga, o 5 intentos sospechosos (bloquear Ajustes, apps fuera de la lista…) en 10 min → alerta en la auditoría y **se deja de consultar al modelo 10 min** |
| Coste y avalancha acotados | Tope de llamadas al modelo por hora y por día; tope de eventos aceptados por hora (429); cola acotada (503) |
| Sin rastro de contenido | La auditoría guarda ids, tamaños y códigos fijos, **nunca texto** (ni del usuario ni del modelo) ni tokens; sin registro de acceso. Si no se puede auditar, **no se emite ninguna orden** |
| Audio | WAV validado **antes** de decodificar (PCM16, mono, 16 kHz, ≤ 30 s, tamaño real); se decodifica en memoria con `wave` y se pasa un array a Whisper: nunca se escribe en disco ni se pasan rutas |
| Hermes no se expone | Solo `127.0.0.1`; sin redirecciones; respuesta ≤ 1 MB; el adaptador se niega a arrancar con otra URL |

## Instalación

Supone el servidor ya montado: Hermes con el perfil `zen-brain` escuchando en `127.0.0.1:8642` y `/opt/zen-brain/venv` con
`faster-whisper`.

```bash
# 1. Código (como root)
install -d -o zen -g zen -m 750 /opt/zen-brain/adapter
cp -r brain/zen_brain /opt/zen-brain/adapter/
install -d -o zen -g zen -m 700 /opt/zen-brain/data /opt/zen-brain/audit

# 2. Configuración: copia deploy/env.example a /opt/zen-brain/.env, rellena ZEN_HERMES_API_KEY y ajusta lo demás
install -o zen -g zen -m 600 brain/deploy/env.example /opt/zen-brain/.env
chmod 750 /opt/zen-brain && chmod go-w /opt/zen-brain

# 3. Atajo para administrar (carga el .env y ejecuta como «zen»)
install -m 755 brain/deploy/zenctl /usr/local/bin/zenctl

# 4. Modelo de voz (una vez, con red; luego el servicio funciona sin red)
zenctl fetch-model

# 5. Comprobar y dar de alta el teléfono (el token se muestra UNA vez; pégalo en la app y no lo guardes en otro sitio)
zenctl check
zenctl tokens add redmi

# 6. Servicio
install -m 644 brain/deploy/zen-brain-adapter.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now zen-brain-adapter
systemd-analyze security zen-brain-adapter      # revisa el endurecimiento
```

`serve` se **niega a arrancar** si `.env`, `tokens.json`, el directorio de datos o la auditoría tienen permisos para grupo/otros, o si
algún directorio donde viven puede ser escrito por otros.

### Exponerlo al teléfono (TLS)

```bash
tailscale serve --bg --https=443 http://127.0.0.1:8787
```

En la app: *Capa → Cerebro →* `https://<tu-equipo>.<tu-tailnet>.ts.net` y el token. **No uses `tailscale funnel`**: abriría el
adaptador a internet. Todo llega al adaptador como `127.0.0.1`, así que no hay límite por IP: el acceso lo decide la tailnet (usa ACLs
para que solo tu teléfono llegue a ese puerto).

### Lo que NO puede comprobar el adaptador (hazlo tú)

El peor caso del sistema no está en este código sino en **Hermes**. Antes de usarlo, en el perfil `zen-brain` de Hermes:

- **Herramientas desactivadas** (shell, archivos, red, código, navegador, correo). El prompt dice «no tienes herramientas», pero el
  adaptador no puede imponerlo.
- **Memoria persistente desactivada**. Si Hermes recuerda entre sesiones, un mensaje malicioso podría «enseñarle» algo para después.
  El adaptador usa una sesión nueva por evento (`ZEN_SESSION_MODE=event`), pero eso no frena una memoria propia de Hermes.
- Que la API solo escuche en `127.0.0.1` y solo la alcancen `root` y el usuario `zen`.

## Operación

```bash
zenctl tokens list | add <n> | rotate <n> | revoke <n>   # revocar funciona aunque el resto de la config esté rota
zenctl stats --hours 24      # eventos/min, latencia p50/p95, % no-JSON, % órdenes rechazadas, tokens, coste, alertas
zenctl check                 # configuración y permisos
```

- **Salud**: `GET /healthz` (sin datos). Las métricas salen de `stats`, no de la red.
- **Alertas** (líneas `type":"alert"` en `audit.jsonl`): `nonjson_burst`, `block_burst`, `pause_burst`, `rejected_burst`, `hermes_auth`,
  `tokens_broken`. Las cuatro primeras ponen el cortacircuitos (10 min sin consultar al modelo).
- **Kill switch**: en el teléfono, la pausa de la capa corta **todo** envío y toda petición (no depende del servidor). En el servidor,
  `zenctl tokens revoke <n>` corta ese dispositivo al instante, o `systemctl stop zen-brain-adapter`.
- **Auditoría**: `/opt/zen-brain/audit/audit.jsonl` (JSON Lines, 600). Rota a 20 MB conservando 5. Con `chattr +a` solo se podrá añadir
  (la rotación del propio adaptador necesita quitarlo; si lo usas, rota con logrotate).

## Decisiones respecto al spec original

- **Protocolo con el teléfono**: se mantiene el que la app ya habla (probado): lotes en `/v1/events`, audio WAV crudo en `/v1/audio`
  (no `wav_b64`: pesa un tercio menos) y las órdenes se **recogen** con `GET /v1/commands`. El teléfono nunca *manda* órdenes ni las
  pide con contenido: solo recoge las que decidió el adaptador, aisladas por token (el parámetro `device` de la URL se ignora).
- **Eventos de la app** que no son notificación, pantalla o voz (`usage`, `system`, `override`) se aceptan y se descartan (si se rechazaran,
  el teléfono reintentaría). `screen` llega al adaptador pero **no al modelo** salvo que lo actives en `ZEN_MODEL_KINDS`.
- **Defectos más estrictos que el spec**: bloqueos de 240 min máx. (el spec admite 1440; súbelo con `ZEN_BLOCK_MAX_MINUTES`), pausas de 120,
  lista de apps bloqueables vacía, sesión nueva por evento.
- **Transcripción vacía**: si Whisper falla o devuelve vacío, el evento se registra y **no se llama al modelo** (el spec lo mandaba igual
  para recibir `{"commands": []}`: gasto sin información).
- **Auditoría**: el spec pide `commands_applied`; el adaptador no puede saber si el teléfono aplicó una orden (no hay confirmación),
  así que registra `commands_emitted` y, cuando el teléfono las recoge, una línea `delivery`.
- **Token por entorno** (`ZEN_PHONE_TOKEN`): ya **no se admite** (no se podía revocar ni rotar sin reiniciar).
- **Cifrado de la cola en el teléfono**: ver `docs/XIAOMI-LAYER.md` (token, eventos en cola y audio van cifrados con el Keystore; la
  memoria con búsqueda no).

## Límites conocidos

- **Slowloris sin credenciales**: todo llega desde `127.0.0.1`, así que no se puede limitar por IP. Un cliente de la tailnet sin token solo
  retiene una ranura 4 s (ventana de cabeceras) y hay 64 ranuras, pero puede mantenerlas ocupadas reabriendo conexiones. Protege el puerto
  con ACLs de Tailscale.
- **Un evento aceptado y perdido**: la cola de eventos vive en memoria. Si el servicio se reinicia a mitad, los eventos en cola (ya
  respondidos con 200) no se procesan. Es a propósito: nada de contenido se guarda en disco.
- **Evento descartado por cortacircuitos, error del modelo o tiempo agotado**: queda como visto y **no se reintenta** (no se gasta dos veces).
- **Requiere Python ≥ 3.10**. Las pruebas se ejecutan aquí con 3.13.

## Pruebas

```bash
cd brain && python3 -m unittest discover -s . -t . -p "test_*.py"     # ~140 pruebas, ~40 s, sin dependencias
```

Cubren: autenticación y rotación de tokens, límites HTTP (tamaños, framing, ranuras, plazos), saneado y anti-inyección, esquema de la
salida del modelo, políticas y presupuestos, cortacircuitos, auditoría (sin texto, rotación, fallos de disco), cliente de Hermes
(reintentos, redirecciones, tiempos), WAV hostiles, SQLite y el recorrido completo contra el servidor real.
