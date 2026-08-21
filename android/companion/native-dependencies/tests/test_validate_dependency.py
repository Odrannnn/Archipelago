import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "validate_dependency.py"
SPEC = importlib.util.spec_from_file_location("validate_dependency", SCRIPT)
assert SPEC and SPEC.loader
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)

BUILDER_SCRIPT = Path(__file__).parents[1] / "scripts" / "build_generic_dependency.py"
BUILDER_SPEC = importlib.util.spec_from_file_location("build_generic_dependency", BUILDER_SCRIPT)
assert BUILDER_SPEC and BUILDER_SPEC.loader
builder = importlib.util.module_from_spec(BUILDER_SPEC)
BUILDER_SPEC.loader.exec_module(builder)


class ValidateDependencyTests(unittest.TestCase):
    def test_reads_aarch64_elf_header(self) -> None:
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        header[18:20] = (183).to_bytes(2, "little")
        self.assertEqual(183, validator.elf_machine(bytes(header)))

    def test_rejects_32_bit_elf(self) -> None:
        header = bytearray(64)
        header[:6] = b"\x7fELF\x01\x01"
        with self.assertRaisesRegex(ValueError, "64-bit"):
            validator.elf_machine(bytes(header))

    def test_rejects_parent_archive_path(self) -> None:
        with self.assertRaisesRegex(ValueError, "Unsafe dependency path"):
            validator.safe_path("site-packages/../../escape.so")

    def test_finds_package_and_extension_modules(self) -> None:
        self.assertTrue(validator.module_present(
            ["site-packages/demo/__init__.py", "site-packages/demo/native.so"], "demo"
        ))
        self.assertTrue(validator.module_present(
            ["site-packages/demo.cpython-312.so"], "demo"
        ))

    def test_packages_and_validates_generic_wheel(self) -> None:
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        header[18:20] = (183).to_bytes(2, "little")
        request = {
            "package": "native-demo",
            "version": "1.2.3",
            "module": "native_demo",
            "python_abi": "cp312",
            "android_abi": "arm64-v8a",
            "minimum_sdk": 26,
            "source_url": "https://files.pythonhosted.org/packages/native-demo.tar.gz",
            "source_sha256": "a" * 64,
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wheel = root / "native_demo-1.2.3-cp312-cp312-android_26_arm64_v8a.whl"
            with zipfile.ZipFile(wheel, "w") as archive:
                archive.writestr("native_demo/__init__.py", "")
                archive.writestr("native_demo/native.so", bytes(header))
                archive.writestr(
                    "native_demo-1.2.3.dist-info/METADATA",
                    "Metadata-Version: 2.1\nName: native-demo\nVersion: 1.2.3\n",
                )
            artifact, entry = builder.package_wheel(wheel, request, root / "dist")
            report = validator.validate(entry, artifact.parent)

        self.assertEqual(artifact.name, report["artifact"])
        self.assertEqual(["site-packages/native_demo/native.so"], report["native_libraries"])


if __name__ == "__main__":
    unittest.main()
