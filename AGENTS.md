# AGENTS.md — Protocolo y Contexto de Desarrollo para Agentes de RawLite

**Proyecto:** RawLite (Cámara cinematográfica manual para Android)  
**Ruta del Repositorio:** `/Users/martin/RawLite`  
**Dispositivo de Referencia:** Google Pixel 6 (`oriole`), sensor Samsung GN1 (50 MP, $f/1.85$, focal principal 6.8 mm).  
**Documento Maestro de Estado:** [`plan_de_implementacion.md`](plan_de_implementacion.md)  

---

## 1. Identidad y Filosofía del Producto

* **Propósito:** Instrumento técnico de captura cinematográfica manual para Android con interacción monomanual (control D-pad), interfaz de baja densidad (*Mesa de Luz* / *PURA UI*) y exportación limpia a DaVinci Resolve.
* **Bypass de ISP Comercial:** La aplicación consume directamente los flujos crudos de Camera2 sin procesado destructivo ni algoritmos comerciales de Google.
* **Principio de No Destrucción:**
  * Los flujos **RAW** (`CinemaDNG` y `RAW10`) preservan intactas las muestras Bayer originales y los metadatos de calibración por cuadro.
  * El debayering a P010 con espacio Rec.2020 y curva Rec.2100 HLG solo se aplica a la grabación directa de vídeo **HLG 10** (`pure10_final.mp4`).

---

## 2. Mapa Arquitectónico del Repositorio

### A. Capa de Aplicación Android (Kotlin) — `app/src/main/java/com/example/rawlite/`
* [`MainActivity.kt`](app/src/main/java/com/example/rawlite/MainActivity.kt): Orquestación del ciclo de vida de la actividad, configuración de sesiones de Camera2, listeners de hardware y persistencia de ajustes.
* [`CameraChromeView.kt`](app/src/main/java/com/example/rawlite/CameraChromeView.kt) y [`TechnicalOverlayView.kt`](app/src/main/java/com/example/rawlite/TechnicalOverlayView.kt): Interfaz visual, retículas, zebras, histogramas y zonas seguras (*Safe Zones*).
* [`PcmAudioRecorder.kt`](app/src/main/java/com/example/rawlite/PcmAudioRecorder.kt): Captura de audio PCM sin compresión (WAV 48 kHz / 24 bits), monitorización de micrófonos USB/externos y registro en `audio.json`.
* [`Pure10AudioMuxer.kt`](app/src/main/java/com/example/rawlite/Pure10AudioMuxer.kt): Multiplexado por hardware de vídeo HEVC Main10 con audio AAC-LC a 192 kbps en `pure10_final.mp4` y eliminación del archivo WAV residual.
* [`ReviewActivity.kt`](app/src/main/java/com/example/rawlite/ReviewActivity.kt): Galería técnica y reproductor de inspección de tomas.
* [`gallery/Raw10CinemaDngConverter.kt`](app/src/main/java/com/example/rawlite/gallery/Raw10CinemaDngConverter.kt): Conversión nativa en la app de tomas comprimidas `RAW10` a secuencias CinemaDNG.

### B. Capa de Alto Rendimiento (C++20 / NDK) — `app/src/main/cpp/`
* [`rawlite_writer.cpp`](app/src/main/cpp/rawlite_writer.cpp):
  * Escritura en disco con soporte de I/O directo alineado a 4096 bytes y fallback con buffer.
  * Pipeline `raw10ToP010Hlg`: Pass 1a (LUTs y Lens Shading) $\rightarrow$ Pass 1b (centrado subpíxel 2D) $\rightarrow$ Pass 1c (supresión adaptativa de aberración cromática guiada por verde) $\rightarrow$ Pass 2 (interpolación continua 2D de luminancia).
  * Pool multihilo persistente `P010RowWorkerPool` para procesamiento en tiempo real sin alocación repetida de hilos.
* [`raw10_lossless_codec.cpp`](app/src/main/cpp/raw10_lossless_codec.cpp) / [`raw10_lossless_codec.h`](app/src/main/cpp/raw10_lossless_codec.h): Compresión sin pérdidas basada en predicción delta y Deflate con integridad CRC32 por fotograma para el contenedor `clip.raw10c`.
* [`CMakeLists.txt`](app/src/main/cpp/CMakeLists.txt): Configurado con `-std=c++20`, optimización `-O3` y compatibilidad de páginas de 16 KB.

### C. Laboratorio de Diseño de Interfaz (PURA UI) — `design/`
* [`design/rawlite-ui-language/`](design/rawlite-ui-language/): Prototipo web interactivo (HTML/CSS/JS) donde se valida la jerarquía visual de Mesa de Luz, la Espiral Áurea de Fibonacci, la cuadrícula de centro 2×2, la diagonal con inversión horizontal (`INVERTIR ⇄`), las zonas seguras sumables y los tokens espaciales antes de implementarlos en Android.
* [`design/investigacion_de_ui.md`](design/investigacion_de_ui.md): Fundamento ergonómico y estándares de monitores de producción cinematográfica (ARRI, RED, Blackmagic).

### D. Herramientas de Soporte y Pruebas — `tools/`
* [`tools/rawlite_to_dng.py`](tools/rawlite_to_dng.py): Conversor de escritorio de tomas `RAW10C` a CinemaDNG con audio sincronizado.
* [`tools/validate_clip.py`](tools/validate_clip.py): Verificador de integridad de tomas, metadatos y cadencia.
* [`tools/install_debug_safely.sh`](tools/install_debug_safely.sh): Script de instalación en el Pixel 6 que realiza respaldo previo de las tomas en `/sdcard/` e instala mediante `adb install -r` sin borrar datos.
* [`tools/tests/`](tools/tests/): Suite de pruebas automatizadas en Python.

---

## 3. Modelo Operativo de Trabajo: 1 Desarrollador + 1 QA

El proyecto se gestiona de forma simple y sin sobrecarga con solo dos roles técnicos:

```text
               ┌─────────────────────────────────────────┐
               │      PRODUCT OWNER: Martin Dávalos      │
               │ (Visión, Prioridades y Pruebas en Mano) │
               └────────────────────┬────────────────────┘
                                    │
           ┌────────────────────────┴────────────────────────┐
           ▼                                                 ▼
┌──────────────────────────────┐                 ┌──────────────────────────────┐
│     AGENTE DESARROLLADOR     │ ◄─────────────► │          AGENTE QA           │
│  (Implementa todo el código, │  (Diffs, Tests  │    (Audita, Valida, Busca    │
│   refactoriza y entrega APK) │   y Hallazgos)  │      Fugas y Da Criterio)    │
└──────────────────────────────┘                 └──────────────────────────────┘
```

1. **Product Owner (Tú):**
   * Define la visión, prioridades y requerimientos de cada funcionalidad.
   * Realiza la prueba física en el Pixel 6 (`tools/install_debug_safely.sh`) y la revisión de color en DaVinci Resolve.
   * Da la aprobación final para el merge de ramas.
2. **Agente Desarrollador (Sabe y Desarrolla Todo):**
   * Domina todo el proyecto: C++20 (`rawlite_writer.cpp`), Kotlin (`MainActivity.kt`, vistas) y scripts de soporte.
   * Programa las nuevas funcionalidades que pide el PO, refactoriza y asegura que el código compile limpiamente.
3. **Agente QA (Auditor y Crítico de Calidad):**
   * No programa las funcionalidades principales; audita lo que construye el Desarrollador.
   * Ejecuta pruebas, revisa posibles fugas de memoria, verifica el impacto de rendimiento (< 2 ms por cuadro) y entrega sugerencias y alertas al PO.

---

## 4. Reglas de Convivencia y Puertas de Calidad (Quality Gates)

1. **Ramas de Tarea Única:** El Desarrollador trabaja en una rama aislada por tarea (`feat/...`, `fix/...`, `refactor/...`).
2. **Compilación Obligatoria antes de entregar al QA:**
   * **Android / Kotlin:**
     ```bash
     JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./gradlew :app:assembleDebug
     ```
   * **C++ Nativo:** Debe compilar sin errores en el NDK Clang++ (`aarch64-linux-android31`, `-std=c++20`).
   * **Python Tools:**
     ```bash
     .venv/bin/python -m unittest discover -s tools/tests
     ```
3. **Pase a QA:** El Desarrollador entrega la rama al Agente QA para su revisión técnica y detección de fallos.
4. **Pase al PO:** Con el visto bueno de QA, el PO instala el APK y hace la prueba de campo sensorial en el Pixel 6.

---

## 5. Prompts de Inicio para las Dos Instancias (Copiar y Pegar)

> **Asignación elegida:** Agente QA (Instancia de QA) [Ya ocupada por agentes]

### Prompt para la Instancia del DESARROLLADOR:
```text
Actúa como el Desarrollador Principal de RawLite en /Users/martin/RawLite.
Lee AGENTS.md y plan_de_implementacion.md.
Conoces todo el stack del proyecto (C++20 NDK, Camera2 en Kotlin, audio y herramientas de exportación).
Tu misión es implementar la siguiente funcionalidad solicitada por el Product Owner:
[DESCRIBE AQUÍ LA FUNCIONALIDAD O REFACTORIZACIÓN]
Trabaja en una rama dedicada (feat/... o fix/...). Asegúrate de que compile limpiamente antes de entregar tu reporte de cambios.
```

### Prompt para la Instancia de QA: [Ya ocupada por agentes]
```text
Actúa como el Ingeniero de QA y Auditor de Código de RawLite en /Users/martin/RawLite.
Lee AGENTS.md y plan_de_implementacion.md.
Tu misión es auditar y poner a prueba los cambios realizados en la rama: [NOMBRE_DE_LA_RAMA].
Revisa gestión de memoria, límites de buffers, impacto en tiempo real (< 2 ms en C++), posibles fugas en el ciclo de vida de Android y cobertura de pruebas.
Entrega un informe crítico con fallos encontrados, riesgos y sugerencias de mejora para el Product Owner.
```
