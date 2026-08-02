import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


class SafeInstallTest(unittest.TestCase):
    def test_backup_is_verified_before_non_destructive_install(self) -> None:
        repo = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fake_adb = root / "adb"
            command_log = root / "adb.log"
            apk = root / "app-debug.apk"
            backup_root = root / "backups"
            apk.write_bytes(b"apk")
            fake_adb.write_text(
                textwrap.dedent(
                    """\
                    #!/usr/bin/env python3
                    import os
                    import sys
                    from pathlib import Path

                    args = sys.argv[1:]
                    with Path(os.environ["FAKE_ADB_LOG"]).open("a", encoding="utf-8") as log:
                        log.write(" ".join(args) + "\\n")
                    if args[-1:] == ["get-state"]:
                        print("device")
                    elif "getprop" in args and "ro.product.device" in args:
                        print("oriole")
                    elif "pidof" in args and "com.example.rawlite" in args:
                        raise SystemExit(1)
                    elif any("find '/sdcard/Android/data/com.example.rawlite/files/Movies'" in value for value in args):
                        print("3")
                        print("4")
                    elif "pull" in args:
                        destination = Path(args[-1])
                        destination.mkdir(parents=True, exist_ok=True)
                        (destination / "a.bin").write_bytes(b"abc")
                        (destination / "b.bin").write_bytes(b"defg")
                        print("2 files pulled")
                    elif "install" in args:
                        print("Success")
                    elif "dumpsys" in args and "package" in args and "com.example.rawlite" in args:
                        print("versionName=0.30")
                    """
                ),
                encoding="utf-8",
            )
            fake_adb.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "ADB_BIN": str(fake_adb),
                    "ANDROID_SERIAL": "QA_SERIAL",
                    "RAWLITE_BACKUP_ROOT": str(backup_root),
                    "FAKE_ADB_LOG": str(command_log),
                }
            )

            result = subprocess.run(
                [str(repo / "tools/install_debug_safely.sh"), str(apk)],
                cwd=repo,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            backups = list(backup_root.glob("RawLite_*_QA_SERIAL"))
            self.assertEqual(1, len(backups))
            self.assertTrue((backups[0] / "BACKUP_VERIFIED.txt").is_file())
            commands = command_log.read_text(encoding="utf-8")
            self.assertIn("install -r", commands)
            self.assertNotIn("uninstall", commands)


if __name__ == "__main__":
    unittest.main()
