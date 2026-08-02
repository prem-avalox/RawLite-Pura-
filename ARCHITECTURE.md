# Arquitectura de RawLite

## Objetivo

RawLite adopta una arquitectura modular por funciones con dependencias dirigidas hacia contratos
pequeños. La arquitectura debe permitir adaptar la captura a otros dispositivos sin alterar el
camino ya validado del Pixel 6 ni introducir trabajo adicional durante una grabación.

La migración es incremental: una refactorización nunca puede modificar simultáneamente la
geometría de UI, el contrato de archivos y el motor de captura. Cada extracción conserva primero
el comportamiento de `v0.33-rc1` y después recibe pruebas de caracterización.

## Mapa de responsabilidades

```text
UI de cámara / D-pad
        |
        v
MainActivity (adaptador Android y coordinador durante la migración)
        |
        +-- core/model       modos, parámetros y política de formato
        +-- core/state       política de interacción según el estado operativo
        +-- core/config      perfiles de captura medidos
        +-- camera           descubrimiento y catálogo Camera2
        +-- capture/audio    contrato de sincronización A/V
        +-- capture/metadata contrato clip.json de cada formato
        +-- device           capacidades declaradas y verificadas
        +-- gallery          enumeración y eliminación explícita de tomas
        |
        +-- adaptadores existentes de RAW10, DNG, PURE 10 y PCM
```

`MainActivity` sigue siendo temporalmente el coordinador de sesión para evitar cambiar de una sola
vez callbacks Camera2 que ya funcionan. Las siguientes extracciones serán el coordinador de
captura, la sesión Camera2 y los tres grabadores; sus contratos ya quedan definidos por las capas
anteriores.

## Regla de dependencias

- `core` no conoce `Activity`, vistas, archivos de tomas ni callbacks Camera2.
- `device` describe capacidades; no decide geometría ni inicia grabaciones.
- `camera` traduce Camera2 a opciones de cámara y superficies disponibles.
- `capture` es dueño de formatos, timestamps, metadatos y finalización.
- `gallery` descubre tomas existentes sin reinterpretar ni modificar el contenido.
- `ui` presenta estado y emite intenciones; no selecciona encoders ni escribe archivos.
- Una acción destructiva permanece explícita y pasa por confirmación de UI.

## Perfil de referencia Pixel 6

`Pixel6ReferenceProfile` conserva las métricas de `v0.33-rc1` (`af0d209`):

- Encoder preferido `c2.exynos.hevc.encoder`.
- PURE 10 a 50 Mb/s y ganancia media HLG 3.0.
- Cola PURE 10 de 10 imágenes y 8 escrituras pendientes.
- Cola RAW10 de 8 imágenes, 6 escrituras pendientes y CinemaDNG de 4.
- Preview 1280×960 y 1024×768.
- 15, 24, 25 y 30 FPS.
- Reserva mínima de 512 MiB, checkpoint de 2 s y drenaje de fondo de 3 s.

Esos valores son una especificación comprobada, no valores genéricos. Ningún sondeo de capacidades
puede sobrescribirlos. Cuando exista un perfil de otro teléfono, la selección ocurrirá antes de
crear la sesión y el perfil elegido será inmutable durante la toma.

## Capacidades y calibración

`AndroidDeviceCapabilityProbe` realiza solamente detección pasiva: cámaras RAW_SENSOR/RAW10,
salidas RAW y encoders HEVC Main10/P010. No abre cámaras, no configura sesiones y no crea metraje.

Cada función se clasifica independientemente como:

1. `UNSUPPORTED`
2. `DECLARED`
3. `SESSION_VERIFIED`
4. `SUSTAINED_VERIFIED`

PURE 10 exige simultáneamente RAW10 y un encoder hardware HEVC Main10/P010. La disponibilidad
resultante usa la evidencia más débil de ambos componentes. La futura calibración controlada
elevará la evidencia, pero no será un benchmark pesado obligatorio al primer inicio.

Un perfil verificado se invalidará cuando cambien la huella de Android o la versión del esquema.
Las limitaciones temporales por temperatura y almacenamiento pertenecen al estado de ejecución,
no a las capacidades permanentes del dispositivo.

## Invariantes de regresión

- La UI conserva Open Sans, negro, amarillo `#FFC100`, blanco y rojo reservado.
- No cambia la cuadrícula canónica ni la semántica del D-pad.
- FULL sigue creando CinemaDNG; BIN 2× RAW crea RAW10; PURE 10 exige BIN 2×.
- `clip.json`, `frames.csv`, `clip.state`, `sync.json`, WAV, DNG y MP4 mantienen su contrato.
- La galería únicamente lee hasta que el usuario confirma una eliminación.
- Ninguna prueba local accede a las tomas del teléfono.
- Compilación, pruebas y Lint usan JDK 21.

## Estrategia de migración restante

1. Introducir un coordinador de captura que sea dueño de las transiciones preparar/grabar/finalizar.
2. Encapsular `CameraCaptureSession` y `ImageReader` detrás de un motor Camera2.
3. Implementar grabadores con un contrato común para CinemaDNG, RAW10 y PURE 10.
4. Seleccionar perfiles por capacidades, conservando Pixel 6 como ruta preferente explícita.
5. Añadir persistencia del perfil y calibración opcional con archivos temporales verificables.
6. Separar físicamente UI y reproductor en módulos Gradle cuando los contratos estén estables.

No se crearán módulos Gradle antes del paso 6: hacerlo mientras los callbacks todavía están
entrelazados añadiría fronteras de compilación sin reducir el riesgo de captura.
