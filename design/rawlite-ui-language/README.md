# PURA UI Lab

Prototipo interactivo separado de la aplicación Android. No abre la cámara, no accede al Pixel y
no modifica tomas.

## Abrir

Desde la raíz del repositorio:

```bash
python3 -m http.server 4173 --directory design/rawlite-ui-language
```

Después abre `http://127.0.0.1:4173/`.

## Recorrido

- `TOMAS`: galería responsive, selección por pulsación larga o arrastre y acciones por lote.
- `REPRODUCTOR`: PURE 10, RAW10 y CinemaDNG, preparación de búfer, stepping y diagnóstico.
- `AJUSTES`: consola propuesta con dos niveles principales (`GENERAL` y `PROYECTO`) y un
  tercer nivel dedicado a formato y resolución.
- `LENGUAJE`: tokens, jerarquía tipográfica, estados y la idea de cristal mate estático.

Las teclas `1`, `2`, `3` y `4` cambian de pantalla. `Esc` cierra menús, diálogos o selección.
En el selector de captura, `←` y `→` cambian de rueda, `↑` y `↓` cambian el valor y `Enter`
aplica la combinación.

La franja superior `PURA UI LAB` pertenece únicamente al entorno de revisión; no forma parte
de la interfaz propuesta para Android.
