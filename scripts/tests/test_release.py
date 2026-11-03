import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release", Path(__file__).parents[1] / "release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseGuards(unittest.TestCase):
    SUCCESS = "\n".join(f"INFO: {name} succeeded" for name in (
        "Integrations", "BiliRoamingX settings entrance", "Moss", "OkHttp", "Config",
        "Pegasus hook", "Commercial promotion filters", "Keep trailing home feed card")) + "\n"

    def test_reject_partial_success_even_when_cli_exits_zero(self):
        with self.assertRaises(RuntimeError):
            release.assert_patch_log(self.SUCCESS + "SEVERE: Pegasus hook failed:\nPatchException: missing\n")

    def test_require_core_module_hooks(self):
        with self.assertRaises(RuntimeError):
            release.assert_patch_log("INFO: Integrations succeeded\n")
        release.assert_patch_log(self.SUCCESS)

    def test_reject_smali_errors_even_when_patch_reports_success(self):
        with self.assertRaises(RuntimeError):
            release.assert_patch_log(self.SUCCESS + "[6,8] Invalid register: v18. Must be between v0 and v15, inclusive.\n")

    def test_reject_stale_or_different_apk(self):
        identity = {"package": "tv.danmaku.bili", "version_code": "9170300", "version": "9.17.0"}
        for key in identity:
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                release.assert_identity({**identity, key: "wrong"}, identity)

    def test_corrupt_download_is_never_promoted(self):
        import io
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "client.apk"
            with patch.object(release.urllib.request, "urlopen", return_value=io.BytesIO(b"bad")):
                with self.assertRaises(RuntimeError):
                    release.download("https://example.invalid/client.apk", output, "0" * 64)
            self.assertFalse(output.exists())
            self.assertFalse(output.with_suffix(".apk.part").exists())


if __name__ == "__main__":
    unittest.main()
