# Registro de cambios

## 0.34-alpha1

- Inicia la migración a arquitectura modular por funciones sin modificar la UI ni el flujo de
  captura validado.
- Conserva las métricas de `v0.33-rc1` en un perfil de referencia explícito del Pixel 6.
- Extrae catálogo Camera2, política RAW/CinemaDNG/PURE 10, estado operativo, metadatos de clips,
  sincronización A/V y repositorio de galería.
- Añade detección pasiva de capacidades RAW y HEVC Main10/P010 para futuros perfiles.
- Añade pruebas de regresión del perfil Pixel 6, formatos, disponibilidad y sincronización.
- Compilación y Lint locales validados con JDK 21; validación física pendiente para no mantener el
  teléfono encendido durante esta fase.

## 0.33-rc1

- Amplía de forma acotada las colas PURE 10 del Pixel 6 para absorber picos térmicos y del
  planificador sin cambiar el trabajo del encoder.
- Mantiene PURE 10, RAW10 y CinemaDNG compatibles con los contratos documentados en el roadmap.
