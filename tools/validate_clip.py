#!/usr/bin/env python3
"""Read-only structural and timing validator for a RawLite take directory."""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import struct
import subprocess
import sys
import wave
import zlib
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Report:
    directory: Path
    checks: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)

    def check(self, condition: bool, success: str, failure: str) -> None:
        (self.checks if condition else self.errors).append(success if condition else failure)


def as_int(value: object, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def load_json(path: Path, report: Report, required: bool = True) -> dict:
    if not path.is_file():
        if required:
            report.errors.append(f"Falta {path.name}")
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError("la raíz no es un objeto")
        return value
    except (OSError, ValueError, json.JSONDecodeError) as error:
        report.errors.append(f"{path.name} inválido: {error}")
        return {}


def parse_state(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    result: dict[str, str] = {}
    for line in path.read_text(encoding="ascii", errors="replace").splitlines():
        key, separator, value = line.partition("=")
        if separator and key:
            result[key] = value
    return result


def validate_wav(audio_path: Path, expected: dict, report: Report) -> tuple[int, int] | None:
    if not audio_path.is_file():
        report.errors.append(f"Falta {audio_path.name}")
        return None
    try:
        with wave.open(str(audio_path), "rb") as wav:
            wav_frames = wav.getnframes()
            wav_rate = wav.getframerate()
            wav_channels = wav.getnchannels()
            wav_bits = wav.getsampwidth() * 8
    except (OSError, wave.Error) as error:
        report.errors.append(f"WAV inválido: {error}")
        return None
    report.check(wav_rate == as_int(expected.get("sample_rate"), wav_rate),
                 f"WAV {wav_rate} Hz", "Sample rate WAV/audio.json no coincide")
    report.check(wav_channels == as_int(expected.get("channels"), wav_channels),
                 f"WAV {wav_channels} canal(es)", "Canales WAV/audio.json no coinciden")
    report.check(wav_bits == as_int(expected.get("bits_per_sample"), wav_bits),
                 f"WAV PCM {wav_bits}-bit", "Profundidad WAV/audio.json no coincide")
    report.check(wav_frames == as_int(expected.get("frames"), wav_frames),
                 f"WAV {wav_frames} frames", "Frames WAV/audio.json no coinciden")
    return wav_frames, wav_rate


def validate_audio(directory: Path, metadata: dict, fps: int, report: Report) -> None:
    sync = load_json(directory / "sync.json", report, required=False)
    audio_meta = load_json(directory / "audio.json", report, required=False)
    audio_path = directory / audio_meta.get("file", "audio.wav")
    if not audio_path.is_file():
        if metadata.get("audio_enabled"):
            report.warnings.append("Audio habilitado pero no existe audio.wav finalizado")
        return
    wav_info = validate_wav(audio_path, audio_meta, report)
    if wav_info is None:
        return
    wav_frames, wav_rate = wav_info
    if sync:
        report.check(sync.get("finalized") is True,
                     "sync.json finalizado", "sync.json no está finalizado")
        offset_ns = sync.get("camera_start_minus_audio_start_ns")
        report.check(isinstance(offset_ns, int),
                     f"Offset A/V {offset_ns / 1e6:.3f} ms" if isinstance(offset_ns, int) else "",
                     "Offset A/V ausente o no numérico")
        first = sync.get("camera_first_timestamp_ns", 0)
        last = sync.get("camera_last_timestamp_ns", 0)
        if isinstance(first, int) and isinstance(last, int) and last > first and wav_rate > 0:
            sample_zero = sync.get("audio_sample_zero_timestamp_ns", 0)
            calculated_audio_end = (
                sample_zero + round(wav_frames * 1_000_000_000 / wav_rate)
                if isinstance(sample_zero, int) and sample_zero > 0
                else 0
            )
            recorded_audio_end = sync.get("audio_end_timestamp_ns")
            if isinstance(recorded_audio_end, int) and calculated_audio_end > 0:
                report.check(
                    abs(recorded_audio_end - calculated_audio_end) <= 1_000_000_000 / wav_rate,
                    "Timestamp final de audio coincide con el WAV",
                    "Timestamp final de audio no coincide con la duración del WAV",
                )
            nominal_frame_ns = 1_000_000_000 / max(fps, 1)
            expected_camera_end = last + nominal_frame_ns
            if calculated_audio_end > 0:
                delta_ms = (calculated_audio_end - expected_camera_end) / 1_000_000
            else:
                camera_seconds = (last - first) / 1e9 + 1 / max(fps, 1)
                available_audio = wav_frames / wav_rate - (offset_ns or 0) / 1e9
                delta_ms = (available_audio - camera_seconds) * 1_000
            limit_ms = 1_000 / max(fps, 1)
            message = f"Cola A/V después del último frame={delta_ms:+.3f} ms"
            if delta_ms >= -limit_ms:
                report.checks.append(message)
            else:
                report.warnings.append(
                    message + f" (el audio termina más de un frame antes: {limit_ms:.3f} ms)"
                )
            if isinstance(offset_ns, int) and offset_ns < -nominal_frame_ns:
                report.warnings.append(
                    f"El audio comienza {-offset_ns / 1e6:.3f} ms después del video"
                )


def validate_raw10(directory: Path, metadata: dict, report: Report) -> None:
    raw = directory / "clip.raw10"
    compressed = directory / "clip.raw10c"
    state = parse_state(directory / "clip.state")
    width = as_int(metadata.get("width"))
    height = as_int(metadata.get("height"))
    row_bytes = as_int(metadata.get("row_bytes"))
    frame_bytes = row_bytes * height
    report.check(width > 0 and height > 0 and row_bytes >= (width * 10 + 7) // 8,
                 f"Geometría RAW10 {width}×{height}, row={row_bytes}",
                 "Geometría o stride RAW10 inválido")
    if frame_bytes <= 0 or (not raw.is_file() and not compressed.is_file()):
        report.errors.append("Falta clip.raw10/clip.raw10c o su geometría es inválida")
        return
    if compressed.is_file():
        complete_stream_frames, complete_bytes, tail, payload_errors = inspect_raw10c(
            compressed, width, height, row_bytes, frame_bytes, report
        )
        report.check(
            payload_errors == 0,
            f"CRC de payload válido en {complete_stream_frames} frames RAW10C",
            f"RAW10C contiene {payload_errors} payload(s) corrupto(s)",
        )
    else:
        complete_stream_frames, tail = divmod(raw.stat().st_size, frame_bytes)
        complete_bytes = complete_stream_frames * frame_bytes
    report.check(tail == 0, f"RAW contiene {complete_stream_frames} frames completos",
                 f"RAW termina con {tail} bytes después del último frame completo")
    completed = as_int(state.get("completed_frames"), complete_stream_frames)
    report.check(0 <= completed <= complete_stream_frames,
                 f"Checkpoint: {completed} frames", "Checkpoint excede el stream RAW")
    expected_state_bytes = complete_bytes if compressed.is_file() else completed * frame_bytes
    state_bytes = as_int(state.get("raw_bytes"), expected_state_bytes)
    report.check(state_bytes == expected_state_bytes,
                 f"Checkpoint de bytes: {state_bytes}",
                 "clip.state no coincide con el tamaño de sus frames completos")
    logical_bytes = as_int(state.get("logical_raw_bytes"), completed * frame_bytes)
    report.check(
        logical_bytes == completed * frame_bytes,
        f"RAW lógico sin pérdida: {logical_bytes} bytes",
        "El tamaño RAW lógico no coincide con los frames completos",
    )
    frames_path = directory / "frames.csv"
    rows: list[dict[str, str]] = []
    if frames_path.is_file():
        with frames_path.open(newline="", encoding="ascii", errors="replace") as source:
            try:
                rows = list(csv.DictReader(source))
            except csv.Error as error:
                report.errors.append(f"frames.csv inválido: {error}")
    else:
        report.errors.append("Falta frames.csv")
    usable = min(completed, complete_stream_frames)
    report.check(len(rows) >= usable, f"Metadatos para {usable} frames",
                 f"Sólo hay {len(rows)} filas de metadatos para {usable} frames")
    try:
        frame_ids = [int(row["frame"]) for row in rows[:usable]]
        timestamps = [int(row["timestamp_ns"]) for row in rows[:usable]]
        report.check(all(a < b for a, b in zip(frame_ids, frame_ids[1:])),
                     "Índices temporales estrictamente crecientes",
                     "Índices temporales duplicados o fuera de orden")
        report.check(all(a < b for a, b in zip(timestamps, timestamps[1:])),
                     "Timestamps estrictamente crecientes",
                     "Timestamps duplicados o fuera de orden")
        gaps = sum(max(0, b - a - 1) for a, b in zip(frame_ids, frame_ids[1:]))
        report.checks.append(f"Huecos temporales: {gaps}")
    except (KeyError, TypeError, ValueError) as error:
        report.errors.append(f"frames.csv no se puede interpretar: {error}")
    report.check(state.get("status") == "closed", "RAW finalizado", "RAW no finalizado")


def inspect_raw10c(
    path: Path,
    width: int,
    height: int,
    row_bytes: int,
    frame_bytes: int,
    report: Report,
) -> tuple[int, int, int, int]:
    size = path.stat().st_size
    with path.open("rb") as source:
        header = source.read(64)
        if len(header) != 64 or header[:8] != b"RLR10C01":
            report.errors.append("Cabecera RAW10C inválida")
            return 0, 0, size, 0
        values = struct.unpack_from("<6I", header, 8)
        expected = (64, 1, width, height, row_bytes, frame_bytes)
        if values != expected:
            report.errors.append(
                f"Cabecera RAW10C incompatible: {values}, esperada {expected}"
            )
            return 0, 0, size, 0
        offset = 64
        frames = 0
        payload_errors = 0
        while offset + 32 <= size:
            source.seek(offset)
            frame_header = source.read(32)
            if len(frame_header) != 32 or frame_header[:4] != b"RLF1":
                break
            header_size, _, _, raw_size, payload_size, _, payload_crc = struct.unpack_from(
                "<7I", frame_header, 4
            )
            payload_offset = offset + 32
            if (
                header_size != 32
                or raw_size != frame_bytes
                or payload_size <= 0
                or payload_size > frame_bytes + 65_536
                or payload_offset + payload_size > size
            ):
                break
            payload = source.read(payload_size)
            if len(payload) != payload_size or zlib.crc32(payload) & 0xFFFFFFFF != payload_crc:
                payload_errors += 1
            frames += 1
            offset = payload_offset + payload_size
        return frames, offset, size - offset, payload_errors


def validate_pure10(directory: Path, metadata: dict, report: Report) -> None:
    video = directory / metadata.get("video_file", "pure10.mp4")
    report.check(video.is_file() and video.stat().st_size > 0,
                 f"MP4 presente ({video.stat().st_size} bytes)" if video.is_file() else "",
                 "Falta pure10.mp4 o está vacío")
    if not video.is_file():
        return
    encoded = as_int(metadata.get("encoded_frames"))
    submitted = as_int(metadata.get("submitted_frames"), encoded)
    report.check(encoded > 0, f"Frames codificados: {encoded}", "PURE 10 no contiene frames")
    report.check(encoded == submitted, "Todos los frames enviados fueron codificados",
                 f"Frames enviados/codificados: {submitted}/{encoded}")
    report.check(not metadata.get("encoder_error"), "Sin error del encoder",
                 f"Error del encoder: {metadata.get('encoder_error')}")
    report.check(metadata.get("finalized") is True, "MP4 finalizado", "MP4 no finalizado")
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        report.warnings.append("ffprobe no disponible; no se validó el bitstream HEVC")
        return
    command = [ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries",
               "stream=codec_name,profile,pix_fmt,color_space,color_transfer,color_primaries,width,height",
               "-of", "json", str(video)]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        report.errors.append(f"ffprobe no pudo leer MP4: {result.stderr.strip()}")
        return
    try:
        stream = (json.loads(result.stdout).get("streams") or [{}])[0]
    except (AttributeError, json.JSONDecodeError, TypeError) as error:
        report.errors.append(f"Respuesta ffprobe inválida: {error}")
        return
    report.check(stream.get("codec_name") == "hevc", "Codec HEVC", "El video no es HEVC")
    report.check("10" in str(stream.get("profile", "")) or "10" in str(stream.get("pix_fmt", "")),
                 f"Perfil/pixel format 10-bit: {stream.get('profile')} {stream.get('pix_fmt')}",
                 "ffprobe no confirma 10 bits")
    report.check(stream.get("color_transfer") in {"arib-std-b67", "hlg"},
                 "Transferencia HLG", f"Transferencia inesperada: {stream.get('color_transfer')}")
    report.check(stream.get("color_primaries") == "bt2020",
                 "Primarias BT.2020",
                 f"Primarias inesperadas: {stream.get('color_primaries')}")
    report.check(stream.get("color_space") in {"bt2020nc", "bt2020_ncl"},
                 "Matriz BT.2020 no constante",
                 f"Matriz inesperada: {stream.get('color_space')}")
    expected_width = as_int(metadata.get("width"))
    expected_height = as_int(metadata.get("height"))
    report.check(
        expected_width <= 0 or as_int(stream.get("width")) == expected_width,
        f"Ancho codificado: {stream.get('width')}",
        f"Ancho MP4/metadata: {stream.get('width')}/{expected_width}",
    )
    report.check(
        expected_height <= 0 or as_int(stream.get("height")) == expected_height,
        f"Alto codificado: {stream.get('height')}",
        f"Alto MP4/metadata: {stream.get('height')}/{expected_height}",
    )


def validate_8bit(directory: Path, metadata: dict, report: Report) -> None:
    video = directory / metadata.get("video_file", "eight_bit.mp4")
    report.check(video.is_file() and video.stat().st_size > 0,
                 f"MP4 presente ({video.stat().st_size} bytes)" if video.is_file() else "",
                 "Falta eight_bit.mp4 o está vacío")
    if not video.is_file():
        return
    encoded = as_int(metadata.get("encoded_frames"))
    submitted = as_int(metadata.get("submitted_frames"), encoded)
    report.check(encoded > 0, f"Frames codificados: {encoded}", "8-BIT no contiene frames")
    report.check(encoded == submitted, "Todos los frames enviados fueron codificados",
                 f"Frames enviados/codificados: {submitted}/{encoded}")
    report.check(not metadata.get("encoder_error"), "Sin error del encoder",
                 f"Error del encoder: {metadata.get('encoder_error')}")
    report.check(metadata.get("finalized") is True, "MP4 finalizado", "MP4 no finalizado")
    ffprobe = shutil.which("ffprobe")
    if not ffprobe:
        report.warnings.append("ffprobe no disponible; no se validó el bitstream AVC")
        return
    command = [ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries",
               "stream=codec_name,profile,pix_fmt,color_space,color_transfer,color_primaries,width,height",
               "-of", "json", str(video)]
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        report.errors.append(f"ffprobe no pudo leer MP4: {result.stderr.strip()}")
        return
    try:
        stream = (json.loads(result.stdout).get("streams") or [{}])[0]
    except (AttributeError, json.JSONDecodeError, TypeError) as error:
        report.errors.append(f"Respuesta ffprobe inválida: {error}")
        return
    report.check(stream.get("codec_name") in {"h264", "avc"}, "Codec AVC (H.264)", f"El video no es AVC: {stream.get('codec_name')}")
    report.check("yuv420p" in str(stream.get("pix_fmt", "")) or "nv12" in str(stream.get("pix_fmt", "")),
                 f"Pixel format 8-bit: {stream.get('pix_fmt')}",
                 "ffprobe no confirma 8 bits")
    report.check(stream.get("color_transfer") in {"bt709", "sdr"},
                 "Transferencia BT.709/SDR", f"Transferencia inesperada: {stream.get('color_transfer')}")
    report.check(stream.get("color_primaries") == "bt709",
                 "Primarias BT.709",
                 f"Primarias inesperadas: {stream.get('color_primaries')}")
    report.check(stream.get("color_space") == "bt709",
                 "Matriz BT.709",
                 f"Matriz inesperada: {stream.get('color_space')}")
    expected_width = as_int(metadata.get("width"))
    expected_height = as_int(metadata.get("height"))
    report.check(
        expected_width <= 0 or as_int(stream.get("width")) == expected_width,
        f"Ancho codificado: {stream.get('width')}",
        f"Ancho MP4/metadata: {stream.get('width')}/{expected_width}",
    )
    report.check(
        expected_height <= 0 or as_int(stream.get("height")) == expected_height,
        f"Alto codificado: {stream.get('height')}",
        f"Alto MP4/metadata: {stream.get('height')}/{expected_height}",
    )


def validate_dng(directory: Path, metadata: dict, report: Report) -> int:
    conversion = load_json(directory / "conversion_report.json", report, required=False)
    fps = as_int(metadata.get("frame_rate"), as_int(conversion.get("fps")))
    report.check(fps in {15, 24, 25, 30}, f"Cadencia nominal: {fps} FPS",
                 f"Cadencia nominal inválida: {fps}")
    files = sorted(directory.glob("frame_*.dng"))
    report.check(bool(files), f"CinemaDNG: {len(files)} archivos", "No hay frames CinemaDNG")
    if not files:
        return fps
    try:
        frame_ids = [as_int(path.stem.removeprefix("frame_"), -1) for path in files]
        report.check(all(value >= 0 for value in frame_ids),
                     "Nombres de frame válidos", "Hay nombres de frame inválidos")
        report.check(all(a < b for a, b in zip(frame_ids, frame_ids[1:])),
                     "Índices DNG estrictamente crecientes",
                     "Índices DNG duplicados o fuera de orden")
        gaps = sum(max(0, b - a - 1) for a, b in zip(frame_ids, frame_ids[1:]))
        report.checks.append(f"Huecos temporales DNG: {gaps}")
    except OSError as error:
        report.errors.append(f"No se pudieron inspeccionar los DNG: {error}")
        frame_ids = []
    invalid_headers = 0
    for path in files:
        try:
            with path.open("rb") as source:
                header = source.read(4)
            if path.stat().st_size <= 8 or header not in {b"II*\x00", b"MM\x00*"}:
                invalid_headers += 1
        except OSError:
            invalid_headers += 1
    report.check(invalid_headers == 0, "Cabeceras TIFF/DNG presentes",
                 f"{invalid_headers} DNG vacíos o con cabecera inválida")
    if conversion:
        source_frames = as_int(conversion.get("source_frames"))
        timeline_frames = as_int(conversion.get("timeline_frames"))
        repeated = as_int(conversion.get("repeated_frame_count"))
        report.check(len(files) == timeline_frames,
                     f"Timeline convertido: {timeline_frames} frames",
                     f"DNG/report: {len(files)}/{timeline_frames} frames")
        report.check(source_frames + repeated == timeline_frames,
                     f"Repetidos para huecos: {repeated}",
                     "Conteo de frames fuente, repetidos y timeline no coincide")
        audio = conversion.get("audio") if isinstance(conversion.get("audio"), dict) else {}
        if audio.get("available"):
            wav_info = validate_wav(directory / audio.get("file", "audio.wav"), audio, report)
            report.check(audio.get("exact_sync") is True,
                         "Audio CinemaDNG alineado por timestamp",
                         "Audio CinemaDNG sin sincronía exacta por timestamp")
            if wav_info and fps > 0:
                wav_frames, wav_rate = wav_info
                expected_frames = round(timeline_frames * wav_rate / fps)
                report.check(wav_frames == expected_frames,
                             "Duración de audio coincide con el timeline DNG",
                             f"Audio/timeline: {wav_frames}/{expected_frames} muestras")
    else:
        saved_frames = as_int(metadata.get("saved_frames"), len(files))
        report.check(len(files) == saved_frames,
                     f"Frames DNG guardados: {saved_frames}",
                     f"DNG/metadata: {len(files)}/{saved_frames} frames")
        report.check(metadata.get("finalized") is True,
                     "CinemaDNG finalizado", "CinemaDNG no finalizado")
        validate_audio(directory, metadata, fps, report)
    return fps


def validate(directory: Path) -> Report:
    report = Report(directory)
    converted_dng = (directory / "conversion_report.json").is_file()
    metadata = load_json(directory / "clip.json", report, required=not converted_dng)
    capture_format = metadata.get("format")
    if capture_format == "rawlite-dng" or converted_dng:
        validate_dng(directory, metadata, report)
        return report
    fps = as_int(metadata.get("frame_rate"))
    report.check(fps in {15, 24, 25, 30}, f"Cadencia nominal: {fps} FPS",
                 f"Cadencia nominal inválida: {fps}")
    if capture_format == "rawlite-raw10":
        validate_raw10(directory, metadata, report)
    elif capture_format in {"rawlite-pure10", "rawlite-10bit"}:
        validate_pure10(directory, metadata, report)
    elif capture_format == "rawlite-8bit":
        validate_8bit(directory, metadata, report)
    else:
        report.errors.append(f"Formato no reconocido: {capture_format!r}")
    validate_audio(directory, metadata, fps, report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Directorio RawLite_... a validar")
    args = parser.parse_args()
    report = validate(args.directory.resolve())
    print(f"RawLite QA: {report.directory}")
    for item in report.checks:
        print(f"  OK    {item}")
    for item in report.warnings:
        print(f"  WARN  {item}")
    for item in report.errors:
        print(f"  ERROR {item}")
    print(f"Resultado: {len(report.errors)} error(es), {len(report.warnings)} advertencia(s)")
    return 1 if report.errors else 0


if __name__ == "__main__":
    sys.exit(main())
