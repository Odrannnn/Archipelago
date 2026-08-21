import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "build_dependency.py"
SPEC = importlib.util.spec_from_file_location("build_dependency", SCRIPT)
assert SPEC and SPEC.loader
build_dependency = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build_dependency)


class PreparePyo3CrossLibTests(unittest.TestCase):
    def test_writes_legacy_pyo3_sysconfig_for_real_target_library(self) -> None:
        recipe = {"android_abi": "arm64-v8a", "python_version": "3.12"}
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            library_directory = target / "jniLibs" / "arm64-v8a"
            library_directory.mkdir(parents=True)
            (library_directory / "libpython3.12.so").touch()

            result = build_dependency.prepare_pyo3_cross_lib(recipe, target)

            self.assertEqual(library_directory, result)
            config = (library_directory / "_sysconfigdata_android.py").read_text(
                encoding="utf-8"
            )
            self.assertIn("'VERSION': '3.9'", config)
            self.assertIn("'LDVERSION': '3.12'", config)
            self.assertIn("'Py_ENABLE_SHARED': 1", config)

    def test_rejects_target_without_matching_libpython(self) -> None:
        recipe = {"android_abi": "arm64-v8a", "python_version": "3.12"}
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(FileNotFoundError, "libpython3.12.so"):
                build_dependency.prepare_pyo3_cross_lib(recipe, Path(temporary))


if __name__ == "__main__":
    unittest.main()
