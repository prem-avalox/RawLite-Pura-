import hashlib
import json
import sys
import tempfile
import unittest
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import rawlite_to_dng  # noqa: E402


class RawLiteToDngAudioTest(unittest.TestCase):
    def test_audio_alignment_does_not_modify_source_take(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            recording = root / "recording"
            output = root / "output"
            recording.mkdir()
            output.mkdir()
            audio_path = recording / "audio.wav"
            self.write_wav(audio_path, frames=48_000)
            (recording / "audio.json").write_text(
                json.dumps(
                    {
                        "sample_rate": 48_000,
                        "channels": 2,
                        "bits_per_sample": 24,
                        "status": "closed",
                        "finalized": True,
                    }
                ),
                encoding="utf-8",
            )
            before = self.digest(audio_path)

            audio_metadata = rawlite_to_dng.inspect_audio(recording)
            report = rawlite_to_dng.align_audio(
                recording=recording,
                output=output,
                audio_metadata=audio_metadata,
                sync={"camera_start_minus_audio_start_ns": 0},
                start_timeline_frame=0,
                output_frames=15,
                fps=15,
                overwrite=False,
            )

            self.assertEqual(before, self.digest(audio_path))
            self.assertTrue(report["available"])
            self.assertEqual(48_000, report["frames"])

    def test_playback_metadata_preserves_orientation_and_aligns_audio_to_zero(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            report = {
                "available": True,
                "file": "audio.wav",
                "exact_sync": True,
                "camera_start_minus_audio_start_ns": 125_000_000,
                "sample_rate": 48_000,
                "channels": 2,
                "bits_per_sample": 24,
                "frames": 96_000,
            }

            rawlite_to_dng.write_playback_metadata(
                output,
                {
                    "format": "rawlite-raw10",
                    "frame_rate": 24,
                    "width": 2032,
                    "height": 1536,
                    "rotation_degrees": 270,
                    "front_facing": False,
                },
                report,
                timeline_frames=48,
            )

            clip = json.loads((output / "clip.json").read_text(encoding="utf-8"))
            audio = json.loads((output / "audio.json").read_text(encoding="utf-8"))
            sync = json.loads((output / "sync.json").read_text(encoding="utf-8"))
            self.assertEqual("rawlite-dng", clip["format"])
            self.assertEqual(270, clip["rotation_degrees"])
            self.assertEqual(48, clip["timeline_frames"])
            self.assertEqual(96_000, audio["frames"])
            self.assertEqual(0, sync["camera_start_minus_audio_start_ns"])
            self.assertEqual(125_000_000, sync["source_camera_start_minus_audio_start_ns"])

    @staticmethod
    def write_wav(path: Path, frames: int) -> None:
        with wave.open(str(path), "wb") as output:
            output.setnchannels(2)
            output.setsampwidth(3)
            output.setframerate(48_000)
            output.writeframes(bytes(frames * 6))

    @staticmethod
    def digest(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == "__main__":
    unittest.main()
