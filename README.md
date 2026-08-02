# RawLite

MVP de captura Bayer RAW para Google Pixel 6, construido sobre Camera2.

## Captura

- Principal, ultra gran angular y frontal cuando el HAL ofrece RAW.
- Sensor completo 4:3 o modo reducido 2x2 del sensor.
- Preview de trabajo 1024x768, alineado al sensor 4:3 para conservar el mismo
  encuadre al entrar y salir de captura RAW. El perfil 1280x960 también está
  disponible en configuración.
- Orientación vertical y horizontal sin interrumpir la toma.
- Interfaz de cámara basada en el SVG `Main Layout`: selectores contextuales,
  rueda horizontal en vertical y rueda vertical en horizontal, ajuste por
  arrastre y botones de precisión.
- ISO, obturación, EV, ganancia post-RAW, balance de blancos y foco.
- Bloqueo de exposición, balance y foco al grabar.
- Selector de 15, 24, 25 o 30 fps aplicado tanto a la captura como al preview.
  En exposición automática, el preview mide durante ocho cuadros y pasa a `A-L`
  para sostener una cadencia exacta. En 2032x1536 RAW10 equivale a unos
  58.5, 93.6, 97.5 o 117 MB/s.
- El flujo RAW desactiva reducción de ruido, enfoque digital, sombreado,
  corrección de distorsión, estabilización y ZSL.
- El modo RAW10 usa un escritor C++/JNI alineado para páginas de 16 KB. Escribe
  directamente desde el buffer de `ImageReader` con `writev()`, sin crear un
  `ByteArray` completo por fotograma.
- La telemetría de temperatura, estado térmico, thermal headroom,
  almacenamiento y duración estimada sigue registrándose, pero no se muestra
  todavía en la interfaz principal.
- La grabación se detiene de forma ordenada al pasar a segundo plano, alcanzar
  estado térmico crítico o quedar menos de 512 MB libres.
- El visor interno enumera PURE 10, RAW10 y CinemaDNG con miniaturas. PURE 10
  usa decodificación HEVC Main10 por hardware; RAW10 y CinemaDNG se reproducen
  como secuencias temporizadas que conservan los huecos de captura. Incluye
  búsqueda, pausa, avance cuadro a cuadro, audio WAV sincronizado, mute y
  borrado con confirmación. No añade proxies ni trabajo durante la grabación.
- Audio PCM a 48 kHz en `audio.wav`: intenta estéreo de 24 bits y baja de forma
  explícita a mono o 16 bits si el HAL de audio no admite la combinación. Usa
  `UNPROCESSED` cuando Android lo declara disponible y `CAMCORDER` como fallback.
- Cámara y audio se sincronizan con `CLOCK_BOOTTIME`. `sync.json` conserva el
  offset exacto entre la primera imagen RAW aceptada y la muestra cero del WAV.
- El medidor de audio indica señal y clipping durante REC. El micrófono se puede
  desactivar antes de iniciar una toma.
- Zebras, false color, focus peaking e histograma permanecen en el código, pero
  están ocultos en la interfaz 0.10 mientras se corrige su precisión. Al estar
  desactivados no se crea la salida YUV adicional ni añaden carga térmica.
- Las herramientas bajan a 4 Hz en estado térmico moderado y se pausan en
  estado severo. Si el HAL rechaza `preview + análisis + RAW`, la aplicación
  continúa grabando sin superposiciones.
- Las matrices ColorMatrix, CameraCalibration y ForwardMatrix que expone el
  HAL se conservan en `clip.json` y se escriben en cada CinemaDNG. El conversor
  también reconoce tomas antiguas de la cámara principal del Pixel 6 y aplica
  su calibración de fábrica.

## Formatos

Cada toma se guarda en:

`Android/data/com.example.rawlite/files/Movies/RawLite_YYYYMMDD_HHMMSS/`

El modo `FULL` produce una secuencia `frame_XXXXXX.dng`. El modo `BIN 2x`
puede producir RAW10 empaquetado o PURE 10. PURE 10 guarda `pure10.mp4` como
HEVC Main10 HLG de 10 bits y conserva el audio PCM por separado. RAW10 produce:

- `clip.raw10`: mosaico Bayer RAW10 concatenado.
- `frames.csv`: ISO, obturación, tiempo, foco y ganancias por fotograma.
- `clip.json`: dimensiones y calibración del sensor.
- `clip.state`: checkpoint incremental y estado `recording`/`closed`.
- `telemetry.csv`: temperatura, estado térmico, almacenamiento y descartes.
- `audio.wav` y `audio.json`: PCM y formato real negociado con Android.
- `sync.json`: relojes, offset audiovisual, duración y huecos de cadencia.

## Conversión para DaVinci Resolve

En macOS:

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements-converter.txt
.venv/bin/python tools/rawlite_to_dng.py /ruta/a/RawLite_YYYYMMDD_HHMMSS
```

La carpeta `dng/` resultante se importa en Resolve como secuencia CinemaDNG.
El conversor también crea `dng/audio.wav`, recortado o rellenado al inicio a
partir de los timestamps y con la misma duración que la secuencia. Importa
ambos elementos, colócalos en el mismo inicio de timeline y enlázalos.
La toma fuente se abre siempre en modo de solo lectura. La salida incluye
`clip.json`, `audio.json` y `sync.json`, por lo que conserva orientación,
geometría y audio también al abrirse en el reproductor interno de RawLite.

Cuando falta un frame, el número temporal se conserva en `frames.csv`. El
conversor genera un hold frame mediante hard link para que Resolve no comprima
el tiempo ni pierda sincronización. `conversion_report.json` enumera todos los
frames repetidos y confirma si el audio tuvo sincronización exacta.

Si una toma no terminó correctamente, el conversor ignora el último frame
incompleto y cualquier línea de metadata parcial. Solo convierte pares RAW +
metadata completos.

El archivo RAW no incluye una apariencia terminada. Es una señal Bayer lineal:
Resolve aplica balance de blancos, matrices de color, curva tonal y espacio de
salida al revelarla. RawLite conserva por cuadro el ISO, la obturación y las
ganancias, y conserva en `clip.json` los niveles negro/blanco y las matrices de
color necesarias para construir los CinemaDNG.

## Límites actuales

- No hay compresión sin pérdida, proxy ni buffer circular.
- El revelado de CinemaDNG y RAW10 para pantalla es por software; en secuencias
  pesadas puede saltar fotogramas para conservar el tiempo y el audio.
- `clip.raw10` sigue siendo un formato propio: se reproduce dentro de RawLite y
  debe convertirse a CinemaDNG para DaVinci Resolve.
- Camera2 no puede mantener ISO automático y obturación manual, o viceversa,
  como dos automatismos independientes: ambos pertenecen al control AE. La UI
  los presenta por separado, pero `AUTO/MAN` cambia el par de exposición.
- La interfaz usa Open Sans incluida en la aplicación.

## Compilación

La versión 0.29 fija Android NDK `27.2.12479018` y CMake `3.22.1`. El binario
arm64 está enlazado con alineación ELF de 16 KB para Android 15 y posteriores.

Todas las tareas Android se ejecutan con JDK 21:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Las tomas se encuentran en almacenamiento privado de la aplicación y Android
las elimina si el paquete se desinstala. Para instalar una compilación de
desarrollo se debe usar `tools/install_debug_safely.sh`: si existen tomas,
primero las copia al Mac, compara el número de archivos y bytes y sólo entonces
ejecuta una instalación `adb install -r`, sin desinstalar ni borrar datos.

Las pruebas instrumentadas viven en `com.example.rawlite.qa`, aisladas del
paquete de producción. Se ejecutan con `tools/run_device_qa.sh`; nunca se debe
usar `connectedDebugAndroidTest` sobre `:app`.

La integridad de una toma RAW10, CinemaDNG o PURE 10 puede comprobarse sin
modificarla:

```bash
python3 tools/validate_clip.py /ruta/a/RawLite_YYYYMMDD_HHMMSS
```
- Los CinemaDNG convertidos son de 16 bits de almacenamiento, pero conservan
  los valores originales de 10 bits y el nivel blanco 1023.
- La apertura es fija. Camera2 no expone un selector público de dual conversion
  gain para el GN1.
- El control de temperatura convierte Kelvin a ganancias RGB aproximadas; no es
  todavía una calibración colorimétrica.
