import json
import sys
import tempfile
import unittest
import wave
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import validate_clip  # noqa: E402


class ValidateClipTest(unittest.TestCase):
    def test_audio_coverage_accepts_a_complete_trailing_pcm_block(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            sample_zero = 1_000_000_000
            camera_first = 1_100_000_000
            camera_last = 2_058_333_333
            wav_frames = 60_000
            audio_end = sample_zero + round(wav_frames * 1_000_000_000 / 48_000)
            self.write_wav(directory / "audio.wav", wav_frames)
            self.write_json(directory / "audio.json", {
                "file": "audio.wav", "sample_rate": 48_000, "channels": 2,
                "bits_per_sample": 24, "frames": wav_frames,
            })
            self.write_json(directory / "sync.json", {
                "finalized": True,
                "camera_first_timestamp_ns": camera_first,
                "camera_last_timestamp_ns": camera_last,
                "audio_sample_zero_timestamp_ns": sample_zero,
                "audio_end_timestamp_ns": audio_end,
                "camera_start_minus_audio_start_ns": camera_first - sample_zero,
            })
            report = validate_clip.Report(directory)

            validate_clip.validate_audio(directory, {"audio_enabled": True}, 24, report)

            self.assertEqual([], report.errors)
            self.assertEqual([], report.warnings)
            self.assertTrue(any("Cola A/V" in item for item in report.checks))

    def test_audio_coverage_warns_when_stop_discards_the_pcm_tail(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            sample_zero = 1_000_000_000
            camera_first = 1_100_000_000
            camera_last = 2_058_333_333
            wav_frames = 48_000
            self.write_wav(directory / "audio.wav", wav_frames)
            self.write_json(directory / "audio.json", {
                "file": "audio.wav", "sample_rate": 48_000, "channels": 2,
                "bits_per_sample": 24, "frames": wav_frames,
            })
            self.write_json(directory / "sync.json", {
                "finalized": True,
                "camera_first_timestamp_ns": camera_first,
                "camera_last_timestamp_ns": camera_last,
                "audio_sample_zero_timestamp_ns": sample_zero,
                "camera_start_minus_audio_start_ns": camera_first - sample_zero,
            })
            report = validate_clip.Report(directory)

            validate_clip.validate_audio(directory, {"audio_enabled": True}, 24, report)

            self.assertEqual([], report.errors)
            self.assertEqual(1, len(report.warnings))
            self.assertIn("audio termina", report.warnings[0])

    def test_converted_dng_checks_timeline_and_aligned_audio(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.write_dng(directory / "frame_000000.dng")
            self.write_dng(directory / "frame_000001.dng")
            self.write_wav(directory / "audio.wav", frames=6_400)
            self.write_json(
                directory / "conversion_report.json",
                {
                    "fps": 15,
                    "source_frames": 1,
                    "timeline_frames": 2,
                    "repeated_frame_count": 1,
                    "audio": {
                        "available": True,
                        "file": "audio.wav",
                        "exact_sync": True,
                        "sample_rate": 48_000,
                        "channels": 2,
                        "bits_per_sample": 24,
                        "frames": 6_400,
                    },
                },
            )

            report = validate_clip.validate(directory)

            self.assertEqual([], report.errors)
            self.assertEqual([], report.warnings)

    def test_direct_dng_preserves_timeline_gap(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.write_dng(directory / "frame_000000.dng")
            self.write_dng(directory / "frame_000002.dng")
            self.write_json(
                directory / "clip.json",
                {
                    "format": "rawlite-dng",
                    "frame_rate": 24,
                    "saved_frames": 2,
                    "timeline_frames": 3,
                    "finalized": True,
                    "audio_enabled": False,
                },
            )

            report = validate_clip.validate(directory)

            self.assertEqual([], report.errors)
            self.assertIn("Huecos temporales DNG: 1", report.checks)

    def test_pure10_can_be_checked_without_ffprobe(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "pure10.mp4").write_bytes(b"fixture")
            self.write_json(
                directory / "clip.json",
                {
                    "format": "rawlite-pure10",
                    "frame_rate": 24,
                    "video_file": "pure10.mp4",
                    "submitted_frames": 2,
                    "encoded_frames": 2,
                    "finalized": True,
                },
            )

            with mock.patch.object(validate_clip.shutil, "which", return_value=None):
                report = validate_clip.validate(directory)

            self.assertEqual([], report.errors)
            self.assertEqual(1, len(report.warnings))

    def test_invalid_numbers_are_reported_instead_of_crashing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.write_json(
                directory / "clip.json",
                {
                    "format": "rawlite-raw10",
                    "frame_rate": "bad",
                    "width": "bad",
                    "height": 0,
                    "row_bytes": None,
                },
            )

            report = validate_clip.validate(directory)

            self.assertGreaterEqual(len(report.errors), 2)

    @staticmethod
    def write_json(path: Path, value: dict) -> None:
        path.write_text(json.dumps(value), encoding="utf-8")

    @staticmethod
    def write_dng(path: Path) -> None:
        path.write_bytes(b"II*\x00" + bytes(12))

    @staticmethod
    def write_wav(path: Path, frames: int) -> None:
        with wave.open(str(path), "wb") as output:
            output.setnchannels(2)
            output.setsampwidth(3)
            output.setframerate(48_000)
            output.writeframes(bytes(frames * 6))


if __name__ == "__main__":
    unittest.main()
