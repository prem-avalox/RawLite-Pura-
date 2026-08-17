#!/usr/bin/env python3
"""Convert a RawLite RAW10 recording to an uncompressed CinemaDNG sequence."""

from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import struct
import wave
import zlib
from fractions import Fraction
from pathlib import Path

import numpy as np
import tifffile


CFA_PATTERNS = {
    "RGGB": (0, 1, 1, 2),
    "GRBG": (1, 0, 2, 1),
    "GBRG": (1, 2, 0, 1),
    "BGGR": (2, 1, 1, 0),
}

RAW10C_MAGIC = b"RLR10C01"
RAW10C_FRAME_MAGIC = b"RLF1"
RAW10C_HEADER_BYTES = 64
RAW10C_FRAME_HEADER_BYTES = 32
RAW10C_FLAG_DEFLATE = 1
RAW10C_FLAG_CFA_DELTA = 2
LENS_SHADING_FILE = "lens_shading.bin"
LENS_SHADING_MAGIC = b"RLLSM001"
LENS_SHADING_RECORD_MAGIC = b"LSM1"


# Pixel 6 main-camera DNG profile extracted from
# PXL_20260805_192832722.RAW-02.ORIGINAL.dng.
PIXEL_6_MAIN_DNG_PROFILE = {
    "color_matrix_1": [
        [12453, 10000], [-6954, 10000], [134, 10000],
        [-2949, 10000], [11718, 10000], [1364, 10000],
        [71, 10000], [1626, 10000], [6290, 10000],
    ],
    "color_matrix_2": [
        [10537, 10000], [-4544, 10000], [-906, 10000],
        [-3178, 10000], [11784, 10000], [1552, 10000],
        [-109, 10000], [1865, 10000], [5324, 10000],
    ],
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert a RawLite RAW10 recording into a CinemaDNG sequence."
    )
    parser.add_argument("recording", type=Path, help="RawLite recording directory")
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        help="Output directory (default: RECORDING/dng)",
    )
    parser.add_argument("--start", type=int, default=0, help="First stream frame")
    parser.add_argument("--count", type=int, help="Maximum number of frames")
    parser.add_argument(
        "--overwrite", action="store_true", help="Replace existing DNG files"
    )
    return parser.parse_args()


def unpack_raw10(frame: np.ndarray, width: int, height: int) -> np.ndarray:
    groups_per_row = width // 4
    packed = frame.reshape(height, -1)[:, : groups_per_row * 5]
    groups = packed.reshape(height, groups_per_row, 5).astype(np.uint16)
    low = groups[:, :, 4]
    output = np.empty((height, groups_per_row, 4), dtype=np.uint16)
    output[:, :, 0] = (groups[:, :, 0] << 2) | (low & 0x03)
    output[:, :, 1] = (groups[:, :, 1] << 2) | ((low >> 2) & 0x03)
    output[:, :, 2] = (groups[:, :, 2] << 2) | ((low >> 4) & 0x03)
    output[:, :, 3] = (groups[:, :, 3] << 2) | ((low >> 6) & 0x03)
    return output.reshape(height, width)


def inverse_cfa_delta(transformed: bytes, width: int, height: int) -> bytes:
    pixel_count = width * height
    expected = pixel_count + (pixel_count + 3) // 4
    if len(transformed) != expected:
        raise ValueError("invalid RAW10C transform size")
    encoded = np.frombuffer(transformed, dtype=np.uint8)
    low = encoded[:pixel_count].astype(np.uint16).reshape(height, width)
    high_packed = encoded[pixel_count:]
    indices = np.arange(pixel_count, dtype=np.int64)
    high = (
        (high_packed[indices // 4] >> ((indices & 3) * 2)) & 0x03
    ).astype(np.uint16).reshape(height, width)
    residual = low | (high << 8)
    mosaic = np.empty((height, width), dtype=np.uint16)
    for parity_y in range(2):
        for parity_x in range(2):
            plane_residual = residual[parity_y::2, parity_x::2].astype(np.uint32)
            row_starts = np.cumsum(plane_residual[:, 0], dtype=np.uint32) & 0x03FF
            plane = np.empty_like(plane_residual)
            plane[:, 0] = row_starts
            if plane.shape[1] > 1:
                plane[:, 1:] = (
                    row_starts[:, None]
                    + np.cumsum(plane_residual[:, 1:], axis=1, dtype=np.uint32)
                ) & 0x03FF
            mosaic[parity_y::2, parity_x::2] = plane.astype(np.uint16)
    groups = mosaic.reshape(height, width // 4, 4)
    packed = np.empty((height, width // 4, 5), dtype=np.uint8)
    packed[:, :, :4] = (groups >> 2).astype(np.uint8)
    packed[:, :, 4] = (
        (groups[:, :, 0] & 0x03)
        | ((groups[:, :, 1] & 0x03) << 2)
        | ((groups[:, :, 2] & 0x03) << 4)
        | ((groups[:, :, 3] & 0x03) << 6)
    ).astype(np.uint8)
    return packed.tobytes()


def read_lens_shading_maps(recording: Path) -> dict[int, tuple[int, int, tuple[float, ...]]]:
    path = recording / LENS_SHADING_FILE
    if not path.is_file():
        return {}
    result: dict[int, tuple[int, int, tuple[float, ...]]] = {}
    with path.open("rb") as stream:
        header = stream.read(32)
        if len(header) != 32 or header[:8] != LENS_SHADING_MAGIC:
            return {}
        version, header_bytes = struct.unpack_from("<II", header, 8)
        if version != 1 or header_bytes < 32:
            return {}
        stream.seek(header_bytes)
        while True:
            record = stream.read(24)
            if len(record) < 24 or record[:4] != LENS_SHADING_RECORD_MAGIC:
                break
            frame, rows, columns, count, expected_crc = struct.unpack_from("<iiiii", record, 4)
            if rows < 2 or columns < 2 or count != rows * columns * 4:
                break
            payload = stream.read(count * 4)
            if len(payload) != count * 4:
                break
            if zlib.crc32(payload) & 0xFFFFFFFF != expected_crc & 0xFFFFFFFF:
                continue
            result[frame] = (rows, columns, struct.unpack(f"<{count}f", payload))
    return result


def gain_map_opcode_list(
    shading: tuple[int, int, tuple[float, ...]], width: int, height: int, cfa: str
) -> bytes:
    rows, columns, gains = shading
    channels = {
        "RGGB": (0, 1, 2, 3),
        "GRBG": (1, 0, 3, 2),
        "GBRG": (1, 3, 0, 2),
        "BGGR": (3, 1, 2, 0),
    }[cfa]
    parameter_bytes = 76 + rows * columns * 4
    output = bytearray(struct.pack(">I", 4))
    for site, channel in enumerate(channels):
        output.extend(struct.pack(">IIII", 9, 0x01030000, 0, parameter_bytes))
        output.extend(
            struct.pack(
                ">IIIIIIIIII",
                site // 2,
                site % 2,
                height,
                width,
                0,
                1,
                2,
                2,
                rows,
                columns,
            )
        )
        output.extend(struct.pack(">ddddI", 1.0 / (rows - 1), 1.0 / (columns - 1), 0.0, 0.0, 1))
        for row in range(rows):
            for column in range(columns):
                output.extend(struct.pack(">f", gains[(row * columns + column) * 4 + channel]))
    return bytes(output)


class Raw10FrameReader:
    def __init__(self, recording: Path, width: int, height: int, row_bytes: int):
        self.width = width
        self.height = height
        self.row_bytes = row_bytes
        self.frame_bytes = row_bytes * height
        self.legacy_path = recording / "clip.raw10"
        self.compressed_path = recording / "clip.raw10c"
        self._legacy = None
        self._stream = None
        self._chunks: list[tuple[int, int, int, int, int]] = []
        if self.compressed_path.is_file():
            self._open_compressed()
        elif self.legacy_path.is_file():
            size = self.legacy_path.stat().st_size
            self.frame_count, self.trailing_bytes = divmod(size, self.frame_bytes)
            self._legacy = np.memmap(self.legacy_path, dtype=np.uint8, mode="r")
        else:
            raise FileNotFoundError("missing clip.raw10 or clip.raw10c")

    def _open_compressed(self) -> None:
        self._stream = self.compressed_path.open("rb")
        header = self._stream.read(RAW10C_HEADER_BYTES)
        if len(header) != RAW10C_HEADER_BYTES or header[:8] != RAW10C_MAGIC:
            raise ValueError("invalid RAW10C header")
        header_bytes, version, width, height, row_bytes, frame_bytes = struct.unpack_from(
            "<6I", header, 8
        )
        if (
            header_bytes != RAW10C_HEADER_BYTES
            or version != 1
            or (width, height, row_bytes, frame_bytes)
            != (self.width, self.height, self.row_bytes, self.frame_bytes)
        ):
            raise ValueError("RAW10C geometry or version does not match clip.json")
        offset = RAW10C_HEADER_BYTES
        size = self.compressed_path.stat().st_size
        while offset + RAW10C_FRAME_HEADER_BYTES <= size:
            self._stream.seek(offset)
            frame_header = self._stream.read(RAW10C_FRAME_HEADER_BYTES)
            if len(frame_header) != RAW10C_FRAME_HEADER_BYTES or frame_header[:4] != RAW10C_FRAME_MAGIC:
                break
            header_size, _, flags, raw_size, payload_size, raw_crc, payload_crc = struct.unpack_from(
                "<7I", frame_header, 4
            )
            payload_offset = offset + RAW10C_FRAME_HEADER_BYTES
            if (
                header_size != RAW10C_FRAME_HEADER_BYTES
                or raw_size != self.frame_bytes
                or payload_size <= 0
                or payload_size > self.frame_bytes + 65_536
                or payload_offset + payload_size > size
            ):
                break
            self._chunks.append((payload_offset, payload_size, flags, raw_crc, payload_crc))
            offset = payload_offset + payload_size
        self.frame_count = len(self._chunks)
        self.trailing_bytes = size - offset

    def read(self, index: int) -> np.ndarray:
        if index < 0 or index >= self.frame_count:
            raise IndexError(index)
        if self._legacy is not None:
            start = index * self.frame_bytes
            return np.asarray(self._legacy[start : start + self.frame_bytes], dtype=np.uint8)
        assert self._stream is not None
        payload_offset, payload_size, flags, raw_crc, payload_crc = self._chunks[index]
        self._stream.seek(payload_offset)
        payload = self._stream.read(payload_size)
        if len(payload) != payload_size or zlib.crc32(payload) & 0xFFFFFFFF != payload_crc:
            raise ValueError(f"RAW10C payload CRC mismatch at frame {index}")
        decoded = zlib.decompress(payload) if flags & RAW10C_FLAG_DEFLATE else payload
        if flags & RAW10C_FLAG_CFA_DELTA:
            decoded = inverse_cfa_delta(decoded, self.width, self.height)
        if len(decoded) != self.frame_bytes or zlib.crc32(decoded) & 0xFFFFFFFF != raw_crc:
            raise ValueError(f"RAW10C raw CRC mismatch at frame {index}")
        return np.frombuffer(decoded, dtype=np.uint8)

    def close(self) -> None:
        if self._stream is not None:
            self._stream.close()
        self._legacy = None

    def __enter__(self) -> "Raw10FrameReader":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


def rational(value: float, limit: int = 1_000_000) -> tuple[int, int]:
    result = Fraction(float(value)).limit_denominator(limit)
    return result.numerator, result.denominator


def matrix_tag(values: list[list[int]]) -> list[tuple[int, int]]:
    if len(values) != 9:
        raise ValueError("The recording has no valid 3x3 color matrix")
    return [(int(pair[0]), int(pair[1])) for pair in values]


def apply_pixel_6_main_dng_profile(metadata: dict) -> bool:
    """Apply the Pixel 6 main-camera color profile extracted from an official DNG."""
    is_pixel_6_main = (
        str(metadata.get("camera_id")) in {"0", "2"}
        and str(metadata.get("camera", "")).startswith("Principal")
        and metadata.get("cfa") == "GBRG"
        and int(metadata.get("width", 0)) in {2032, 4080}
    )
    if not is_pixel_6_main:
        return False
    metadata.update(PIXEL_6_MAIN_DNG_PROFILE)
    metadata["reference_illuminant_1"] = 17
    metadata["reference_illuminant_2"] = 21
    metadata["baseline_exposure"] = 0.59
    metadata["unique_camera_model"] = "Google Pixel 6"
    metadata["color_profile"] = "Google Pixel 6 main DNG"
    metadata["color_profile_source"] = "PXL_20260805_192832722.RAW-02.ORIGINAL.dng"
    for key in (
        "calibration_matrix_1",
        "calibration_matrix_2",
        "forward_matrix_1",
        "forward_matrix_2",
    ):
        metadata.pop(key, None)
    return True


def read_rows(path: Path) -> list[dict[str, str]]:
    required = {
        "frame",
        "timestamp_ns",
        "iso",
        "exposure_ns",
        "frame_duration_ns",
        "focus_diopters",
        "wb_r",
        "wb_ge",
        "wb_go",
        "wb_b",
    }
    output: list[dict[str, str]] = []
    with path.open(newline="", encoding="ascii") as stream:
        for line_number, row in enumerate(csv.DictReader(stream), start=2):
            if not required.issubset(row) or any(row.get(key) in (None, "") for key in required):
                print(f"Warning: ignoring incomplete metadata at line {line_number}")
                break
            try:
                int(row["frame"])
                int(row["timestamp_ns"])
                int(row["iso"])
                int(row["exposure_ns"])
                int(row["frame_duration_ns"])
                float(row["focus_diopters"])
                float(row["wb_r"])
                float(row["wb_ge"])
                float(row["wb_go"])
                float(row["wb_b"])
            except ValueError:
                print(f"Warning: ignoring invalid metadata at line {line_number}")
                break
            output.append(row)
    return output


def read_state(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    output: dict[str, str] = {}
    for line in path.read_text(encoding="ascii", errors="replace").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            output[key] = value
    return output


def load_json(path: Path) -> dict:
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def inspect_audio(recording: Path) -> dict:
    """Inspect recorder audio without ever changing the source take."""
    metadata_path = recording / "audio.json"
    audio_path = recording / "audio.wav"
    metadata = dict(load_json(metadata_path))
    if not metadata or not audio_path.is_file() or audio_path.stat().st_size < 44:
        return metadata
    channels = int(metadata.get("channels", 0))
    sample_rate = int(metadata.get("sample_rate", 0))
    bits = int(metadata.get("bits_per_sample", 0))
    block_align = channels * bits // 8
    if channels <= 0 or sample_rate <= 0 or block_align <= 0:
        return metadata
    payload = max(0, audio_path.stat().st_size - 44)
    payload -= payload % block_align
    if metadata.get("status") != "closed" or not metadata.get("finalized", False):
        print("Reading unfinished audio.wav from file length without modifying the take")
    metadata["frames"] = payload // block_align
    metadata["data_bytes"] = payload
    return metadata


def link_or_copy(source: Path, destination: Path, overwrite: bool) -> None:
    if destination.exists():
        if not overwrite:
            raise SystemExit(f"{destination} exists; use --overwrite to replace it")
        destination.unlink()
    try:
        os.link(source, destination)
    except OSError:
        shutil.copy2(source, destination)


def align_audio(
    recording: Path,
    output: Path,
    audio_metadata: dict,
    sync: dict,
    start_timeline_frame: int,
    output_frames: int,
    fps: int,
    overwrite: bool,
) -> dict:
    source = recording / "audio.wav"
    channels = int(audio_metadata.get("channels", 0))
    sample_rate = int(audio_metadata.get("sample_rate", 0))
    bits = int(audio_metadata.get("bits_per_sample", 0))
    sample_width = bits // 8
    frame_bytes = channels * sample_width
    if (
        not source.is_file()
        or source.stat().st_size < 44
        or output_frames <= 0
        or fps <= 0
        or channels <= 0
        or sample_rate <= 0
        or sample_width not in (2, 3)
        or frame_bytes <= 0
    ):
        return {"available": False}
    destination = output / "audio.wav"
    if destination.exists() and not overwrite:
        raise SystemExit(f"{destination} exists; use --overwrite to replace it")
    offset_ns = sync.get("camera_start_minus_audio_start_ns")
    exact_sync = isinstance(offset_ns, int)
    source_frames = max(0, source.stat().st_size - 44) // frame_bytes
    with source.open("rb") as reader:
        timeline_offset_samples = round(start_timeline_frame * sample_rate / fps)
        camera_offset_samples = round(offset_ns * sample_rate / 1e9) if exact_sync else 0
        source_start = camera_offset_samples + timeline_offset_samples
        target_frames = round(output_frames * sample_rate / fps)
        silence_before = max(0, -source_start)
        source_start = max(0, source_start)
        if destination.exists():
            destination.unlink()
        with wave.open(str(destination), "wb") as writer:
            writer.setnchannels(channels)
            writer.setsampwidth(sample_width)
            writer.setframerate(sample_rate)
            remaining = target_frames
            if silence_before:
                silence = min(silence_before, remaining)
                while silence > 0:
                    chunk = min(silence, sample_rate)
                    writer.writeframesraw(bytes(chunk * frame_bytes))
                    silence -= chunk
                    remaining -= chunk
            if source_start < source_frames and remaining > 0:
                reader.seek(44 + source_start * frame_bytes)
                available = min(remaining, source_frames - source_start)
                while available > 0:
                    chunk = min(available, sample_rate)
                    data = reader.read(chunk * frame_bytes)
                    if not data:
                        break
                    frames_read = len(data) // frame_bytes
                    writer.writeframesraw(data[: frames_read * frame_bytes])
                    available -= frames_read
                    remaining -= frames_read
            while remaining > 0:
                chunk = min(remaining, sample_rate)
                writer.writeframesraw(bytes(chunk * frame_bytes))
                remaining -= chunk
    if not exact_sync:
        print("Warning: camera/audio clocks were not comparable; audio starts without exact trim")
    return {
        "available": True,
        "file": destination.name,
        "exact_sync": exact_sync,
        "camera_start_minus_audio_start_ns": offset_ns,
        "sample_rate": sample_rate,
        "channels": channels,
        "bits_per_sample": sample_width * 8,
        "frames": target_frames,
        "duration_seconds": target_frames / sample_rate,
    }


def write_playback_metadata(
    output: Path,
    source_metadata: dict,
    audio_report: dict,
    timeline_frames: int,
) -> None:
    clip_metadata = dict(source_metadata)
    clip_metadata.update(
        {
            "format": "rawlite-dng",
            "version": 1,
            "mode": "RAW",
            "container": "CinemaDNG sequence",
            "bits_per_sample": 16,
            "saved_frames": timeline_frames,
            "timeline_frames": timeline_frames,
            "finalized": True,
            "source_format": source_metadata.get("format"),
        }
    )
    (output / "clip.json").write_text(
        json.dumps(clip_metadata, indent=2), encoding="utf-8"
    )
    if not audio_report.get("available"):
        return
    frame_bytes = (
        int(audio_report["channels"]) * int(audio_report["bits_per_sample"]) // 8
    )
    audio_metadata = {
        "format": "wav-pcm",
        "status": "closed",
        "file": audio_report["file"],
        "sample_rate": audio_report["sample_rate"],
        "channels": audio_report["channels"],
        "bits_per_sample": audio_report["bits_per_sample"],
        "frames": audio_report["frames"],
        "data_bytes": int(audio_report["frames"]) * frame_bytes,
        "finalized": True,
    }
    (output / "audio.json").write_text(
        json.dumps(audio_metadata, indent=2), encoding="utf-8"
    )
    sync_metadata = {
        "version": 1,
        "video_clock": "CINEMADNG_TIMELINE",
        "audio_clock": "CINEMADNG_TIMELINE",
        "camera_start_minus_audio_start_ns": 0,
        "source_camera_start_minus_audio_start_ns": audio_report.get(
            "camera_start_minus_audio_start_ns"
        ),
        "audio_file": audio_report["file"],
        "audio_sample_rate": audio_report["sample_rate"],
        "audio_channels": audio_report["channels"],
        "audio_bits_per_sample": audio_report["bits_per_sample"],
        "audio_frames": audio_report["frames"],
        "finalized": True,
    }
    (output / "sync.json").write_text(
        json.dumps(sync_metadata, indent=2), encoding="utf-8"
    )


def dng_tags(
    metadata: dict,
    row: dict[str, str],
    shading: tuple[int, int, tuple[float, ...]] | None = None,
) -> list[tuple[int, str, int, object, bool]]:
    width = int(metadata["width"])
    height = int(metadata["height"])
    black = [rational(float(value)) for value in metadata["black_level"]]
    wb_r = max(float(row["wb_r"]), 0.0001)
    wb_g = max((float(row["wb_ge"]) + float(row["wb_go"])) / 2.0, 0.0001)
    wb_b = max(float(row["wb_b"]), 0.0001)
    neutral = [1.0 / wb_r, 1.0 / wb_g, 1.0 / wb_b]
    scale = max(neutral)
    neutral = [rational(value / scale) for value in neutral]
    camera_name = str(metadata["camera"]).encode("ascii", "replace").decode("ascii")
    is_pixel_6 = (
        str(metadata.get("camera_id")) in {"0", "2"}
        and str(metadata.get("camera", "")).startswith("Principal")
        and metadata.get("cfa") == "GBRG"
    )
    camera_make = "Google" if is_pixel_6 else "RawLite"
    camera_model = "Pixel 6" if is_pixel_6 else camera_name

    tags: list[tuple[int, str, int, object, bool]] = [
        (254, "I", 1, 0, False),
        (271, "s", 0, camera_make, False),
        (272, "s", 0, camera_model, False),
        (274, "H", 1, 1, False),
        (33421, "H", 2, (2, 2), False),
        (33422, "B", 4, CFA_PATTERNS[metadata["cfa"]], False),
        (33434, "2I", 1, rational(int(row["exposure_ns"]) / 1e9), False),
        (34855, "H", 1, int(row["iso"]), False),
        (50706, "B", 4, (1, 4, 0, 0), False),
        (50707, "B", 4, (1, 3, 0, 0) if shading else (1, 1, 0, 0), False),
        (50708, "s", 0, metadata.get("unique_camera_model", f"RawLite {camera_name}"), False),
        (50710, "B", 3, (0, 1, 2), False),
        (50711, "H", 1, 1, False),
        (50713, "H", 2, (2, 2), False),
        (50714, "2I", 4, black, False),
        (50717, "I", 1, int(metadata["white_level"]), False),
        (50718, "2I", 2, ((1, 1), (1, 1)), False),
        (50719, "I", 2, (0, 0), False),
        (50720, "I", 2, (width, height), False),
        (50721, "2i", 9, matrix_tag(metadata["color_matrix_1"]), False),
        (50722, "2i", 9, matrix_tag(metadata["color_matrix_2"]), False),
        (50728, "2I", 3, neutral, False),
        (
            50778,
            "H",
            1,
            int(metadata.get("reference_illuminant_1", 21)),
            False,
        ),
        (
            50779,
            "H",
            1,
            int(metadata.get("reference_illuminant_2", 17)),
            False,
        ),
        (50829, "I", 4, (0, 0, height, width), False),
    ]
    optional_matrices = (
        ("calibration_matrix_1", 50723),
        ("calibration_matrix_2", 50724),
        ("forward_matrix_1", 50964),
        ("forward_matrix_2", 50965),
    )
    if "baseline_exposure" in metadata:
        tags.append((50730, "2i", 1, rational(float(metadata["baseline_exposure"])), False))
    for key, tag in optional_matrices:
        values = metadata.get(key, [])
        if len(values) == 9:
            tags.append((tag, "2i", 9, matrix_tag(values), False))
    if shading:
        opcode_list = gain_map_opcode_list(shading, width, height, metadata["cfa"])
        tags.append((51009, "B", len(opcode_list), tuple(opcode_list), False))
    return tags


def write_dng(
    destination: Path,
    mosaic: np.ndarray,
    metadata: dict,
    row: dict[str, str],
    shading: tuple[int, int, tuple[float, ...]] | None = None,
) -> None:
    """Write a conservative, uncompressed DNG accepted as CinemaDNG by Resolve."""
    tifffile.imwrite(
        destination,
        mosaic,
        photometric="cfa",
        compression=None,
        metadata=None,
        software="RawLite converter",
        rowsperstrip=1,
        resolution=(72, 72),
        resolutionunit="INCH",
        extratags=dng_tags(metadata, row, shading),
    )
    if shading:
        # tifffile exposes BYTE for arbitrary payloads; DNG mandates UNDEFINED for OpcodeList2.
        with destination.open("r+b") as stream:
            stream.seek(4)
            ifd_offset = struct.unpack("<I", stream.read(4))[0]
            stream.seek(ifd_offset)
            entry_count = struct.unpack("<H", stream.read(2))[0]
            for entry in range(entry_count):
                offset = ifd_offset + 2 + entry * 12
                stream.seek(offset)
                tag = struct.unpack("<H", stream.read(2))[0]
                if tag == 51009:
                    stream.write(struct.pack("<H", 7))
                    break


def main() -> None:
    args = parse_args()
    recording = args.recording.expanduser().resolve()
    with (recording / "clip.json").open(encoding="utf-8") as stream:
        metadata = json.load(stream)
    if metadata.get("format") != "rawlite-raw10":
        raise SystemExit("clip.json is not a RawLite RAW10 recording")
    if apply_pixel_6_main_dng_profile(metadata):
        print("Using Pixel 6 main-camera DNG color profile")

    rows = read_rows(recording / "frames.csv")
    lens_shading_maps = read_lens_shading_maps(recording)
    state = read_state(recording / "clip.state")
    if state.get("status") not in (None, "closed"):
        print(
            "Warning: recording was not finalized; conversion will use only complete "
            "RAW frames with valid metadata"
        )
    width = int(metadata["width"])
    height = int(metadata["height"])
    row_bytes = int(metadata["row_bytes"])
    frame_bytes = row_bytes * height
    reader = Raw10FrameReader(recording, width, height, row_bytes)
    stream_frames = reader.frame_count
    if reader.trailing_bytes:
        print(
            f"Warning: ignoring {reader.trailing_bytes} trailing bytes from an incomplete RAW frame"
        )
    available = min(stream_frames, len(rows))
    end = available if args.count is None else min(available, args.start + args.count)
    if args.start < 0 or args.start >= end:
        raise SystemExit(f"invalid frame range; recording contains {available} frames")

    output = (args.output or recording / "dng").expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    first_timeline_frame = int(rows[args.start]["frame"])
    last_timeline_frame = first_timeline_frame - 1
    previous_destination: Path | None = None
    repeated_frames: list[int] = []

    with reader:
        for stream_index in range(args.start, end):
            row = rows[stream_index]
            frame_id = int(row["frame"])
            if frame_id <= last_timeline_frame:
                print(
                    f"\nWarning: non-monotonic frame id {frame_id}; "
                    f"placing it at {last_timeline_frame + 1}"
                )
                frame_id = last_timeline_frame + 1
            if previous_destination is not None:
                for missing_id in range(last_timeline_frame + 1, frame_id):
                    repeated = output / f"frame_{missing_id:06d}.dng"
                    link_or_copy(previous_destination, repeated, args.overwrite)
                    previous_destination = repeated
                    repeated_frames.append(missing_id)
            destination = output / f"frame_{frame_id:06d}.dng"
            if destination.exists() and not args.overwrite:
                raise SystemExit(f"{destination} exists; use --overwrite to replace it")
            if destination.exists():
                destination.unlink()
            mosaic = unpack_raw10(reader.read(stream_index), width, height)
            write_dng(destination, mosaic, metadata, row, lens_shading_maps.get(int(row["frame"])))
            previous_destination = destination
            last_timeline_frame = frame_id
            print(f"\r{stream_index - args.start + 1}/{end - args.start}", end="", flush=True)

    output_frame_count = last_timeline_frame - first_timeline_frame + 1
    audio_metadata = inspect_audio(recording)
    sync = load_json(recording / "sync.json")
    audio_report = align_audio(
        recording=recording,
        output=output,
        audio_metadata=audio_metadata,
        sync=sync,
        start_timeline_frame=first_timeline_frame,
        output_frames=output_frame_count,
        fps=int(metadata.get("frame_rate", 0)),
        overwrite=args.overwrite,
    )
    report = {
        "source": str(recording),
        "output": str(output),
        "fps": int(metadata.get("frame_rate", 0)),
        "source_frames": end - args.start,
        "timeline_frames": output_frame_count,
        "first_timeline_frame": first_timeline_frame,
        "last_timeline_frame": last_timeline_frame,
        "repeated_frame_count": len(repeated_frames),
        "repeated_frames": repeated_frames,
        "audio": audio_report,
        "audio_capture": audio_metadata,
    }
    (output / "conversion_report.json").write_text(
        json.dumps(report, indent=2), encoding="utf-8"
    )
    write_playback_metadata(output, metadata, audio_report, output_frame_count)
    print(
        f"\nWrote {output_frame_count} CinemaDNG timeline frames to {output} "
        f"({len(repeated_frames)} repeated)"
    )
    if audio_report.get("available"):
        precision = "timestamp sync" if audio_report.get("exact_sync") else "unverified start"
        print(f"Wrote aligned audio.wav ({precision})")


if __name__ == "__main__":
    main()
