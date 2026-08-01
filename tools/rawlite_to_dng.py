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


# Pixel 6 main-camera factory matrices exposed by Camera2. Recordings created
# before RawLite 0.8 did not preserve these four matrices in clip.json.
PIXEL_6_MAIN_FACTORY_PROFILE = {
    "calibration_matrix_1": [
        [6611, 6709], [0, 1], [0, 1],
        [0, 1], [1, 1], [0, 1],
        [0, 1], [0, 1], [4131, 4131],
    ],
    "calibration_matrix_2": [
        [4629, 4736], [0, 1], [0, 1],
        [0, 1], [1, 1], [0, 1],
        [0, 1], [0, 1], [7217, 7246],
    ],
    "forward_matrix_1": [
        [4362, 10000], [3850, 10000], [1431, 10000],
        [2226, 10000], [7168, 10000], [606, 10000],
        [139, 10000], [971, 10000], [7141, 10000],
    ],
    "forward_matrix_2": [
        [4361, 10000], [3851, 10000], [1431, 10000],
        [2225, 10000], [7169, 10000], [606, 10000],
        [139, 10000], [971, 10000], [7141, 10000],
    ],
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert clip.raw10 + metadata into a CinemaDNG sequence."
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


def rational(value: float, limit: int = 1_000_000) -> tuple[int, int]:
    result = Fraction(float(value)).limit_denominator(limit)
    return result.numerator, result.denominator


def matrix_tag(values: list[list[int]]) -> list[tuple[int, int]]:
    if len(values) != 9:
        raise ValueError("The recording has no valid 3x3 color matrix")
    return [(int(pair[0]), int(pair[1])) for pair in values]


def add_legacy_factory_profile(metadata: dict) -> bool:
    """Add Pixel 6 factory matrices to recordings made before RawLite 0.8."""
    required = tuple(PIXEL_6_MAIN_FACTORY_PROFILE)
    if all(len(metadata.get(key, [])) == 9 for key in required):
        return False
    is_pixel_6_main = (
        str(metadata.get("camera_id")) == "2"
        and str(metadata.get("camera", "")).startswith("Principal")
        and metadata.get("cfa") == "GBRG"
        and int(metadata.get("width", 0)) in (2032, 4080)
    )
    if not is_pixel_6_main:
        return False
    for key, value in PIXEL_6_MAIN_FACTORY_PROFILE.items():
        metadata.setdefault(key, value)
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


def repair_wave_header(recording: Path) -> dict:
    metadata_path = recording / "audio.json"
    audio_path = recording / "audio.wav"
    metadata = load_json(metadata_path)
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
    if payload > 0xFFFF_FFFF - 36:
        print("Warning: audio.wav exceeds classic RIFF size; leaving its header unchanged")
        return metadata
    header = struct.pack(
        "<4sI4s4sIHHIIHH4sI",
        b"RIFF",
        36 + payload,
        b"WAVE",
        b"fmt ",
        16,
        1,
        channels,
        sample_rate,
        sample_rate * block_align,
        block_align,
        bits,
        b"data",
        payload,
    )
    with audio_path.open("r+b") as stream:
        stream.seek(0)
        stream.write(header)
        stream.truncate(44 + payload)
    if metadata.get("status") != "closed" or not metadata.get("finalized", False):
        print("Recovered audio.wav header from file length")
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
    sync: dict,
    start_timeline_frame: int,
    output_frames: int,
    fps: int,
    overwrite: bool,
) -> dict:
    source = recording / "audio.wav"
    if not source.is_file() or output_frames <= 0 or fps <= 0:
        return {"available": False}
    destination = output / "audio.wav"
    if destination.exists() and not overwrite:
        raise SystemExit(f"{destination} exists; use --overwrite to replace it")
    offset_ns = sync.get("camera_start_minus_audio_start_ns")
    exact_sync = isinstance(offset_ns, int)
    with wave.open(str(source), "rb") as reader:
        channels = reader.getnchannels()
        sample_width = reader.getsampwidth()
        sample_rate = reader.getframerate()
        source_frames = reader.getnframes()
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
            frame_bytes = channels * sample_width
            remaining = target_frames
            if silence_before:
                silence = min(silence_before, remaining)
                while silence > 0:
                    chunk = min(silence, sample_rate)
                    writer.writeframesraw(bytes(chunk * frame_bytes))
                    silence -= chunk
                    remaining -= chunk
            if source_start < source_frames and remaining > 0:
                reader.setpos(source_start)
                available = min(remaining, source_frames - source_start)
                while available > 0:
                    chunk = min(available, sample_rate)
                    data = reader.readframes(chunk)
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


def dng_tags(
    metadata: dict,
    row: dict[str, str],
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

    tags: list[tuple[int, str, int, object, bool]] = [
        (33421, "H", 2, (2, 2), False),
        (33422, "B", 4, CFA_PATTERNS[metadata["cfa"]], False),
        (33434, "2I", 1, rational(int(row["exposure_ns"]) / 1e9), False),
        (34855, "H", 1, int(row["iso"]), False),
        (50706, "B", 4, (1, 4, 0, 0), False),
        (50707, "B", 4, (1, 3, 0, 0), False),
        (50708, "s", 0, f"RawLite {camera_name}", False),
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
    for key, tag in optional_matrices:
        values = metadata.get(key, [])
        if len(values) == 9:
            tags.append((tag, "2i", 9, matrix_tag(values), False))
    return tags


def main() -> None:
    args = parse_args()
    recording = args.recording.expanduser().resolve()
    with (recording / "clip.json").open(encoding="utf-8") as stream:
        metadata = json.load(stream)
    if metadata.get("format") != "rawlite-raw10":
        raise SystemExit("clip.json is not a RawLite RAW10 recording")
    if add_legacy_factory_profile(metadata):
        print("Using Pixel 6 main-camera factory calibration for legacy recording")

    rows = read_rows(recording / "frames.csv")
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
    raw_path = recording / "clip.raw10"
    stream_frames, remainder = divmod(raw_path.stat().st_size, frame_bytes)
    if remainder:
        print(
            f"Warning: ignoring {remainder} trailing bytes from an incomplete RAW frame"
        )
    available = min(stream_frames, len(rows))
    end = available if args.count is None else min(available, args.start + args.count)
    if args.start < 0 or args.start >= end:
        raise SystemExit(f"invalid frame range; recording contains {available} frames")

    output = (args.output or recording / "dng").expanduser().resolve()
    output.mkdir(parents=True, exist_ok=True)
    raw = np.memmap(raw_path, dtype=np.uint8, mode="r")

    first_timeline_frame = int(rows[args.start]["frame"])
    last_timeline_frame = first_timeline_frame - 1
    previous_destination: Path | None = None
    repeated_frames: list[int] = []

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
        start = stream_index * frame_bytes
        mosaic = unpack_raw10(raw[start : start + frame_bytes], width, height)
        tifffile.imwrite(
            destination,
            mosaic,
            photometric="cfa",
            compression=None,
            metadata=None,
            software="RawLite converter",
            extratags=dng_tags(metadata, row),
        )
        previous_destination = destination
        last_timeline_frame = frame_id
        print(f"\r{stream_index - args.start + 1}/{end - args.start}", end="", flush=True)

    output_frame_count = last_timeline_frame - first_timeline_frame + 1
    audio_metadata = repair_wave_header(recording)
    sync = load_json(recording / "sync.json")
    audio_report = align_audio(
        recording=recording,
        output=output,
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
    print(
        f"\nWrote {output_frame_count} CinemaDNG timeline frames to {output} "
        f"({len(repeated_frames)} repeated)"
    )
    if audio_report.get("available"):
        precision = "timestamp sync" if audio_report.get("exact_sync") else "unverified start"
        print(f"Wrote aligned audio.wav ({precision})")


if __name__ == "__main__":
    main()
