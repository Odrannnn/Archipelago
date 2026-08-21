import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "scripts" / "resolve_build_request.py"
SPEC = importlib.util.spec_from_file_location("resolve_build_request", SCRIPT)
assert SPEC and SPEC.loader
resolver = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(resolver)


def metadata(name="native-demo", version="1.2.3", digest="a" * 64):
    return {
        "info": {"name": name, "version": version},
        "urls": [{
            "packagetype": "sdist",
            "filename": f"{name}-{version}.tar.gz",
            "url": f"https://files.pythonhosted.org/packages/{name}-{version}.tar.gz",
            "digests": {"sha256": digest},
        }],
    }


class ResolveBuildRequestTests(unittest.TestCase):
    def test_generic_request_is_hash_pinned_and_stable(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            recipes = Path(temporary)
            first = resolver.resolve_request(
                "native_demo", "1.2.3", "native_demo", "auto", "3.12",
                "arm64-v8a", 26, recipes, metadata(),
            )
            second = resolver.resolve_request(
                "native-demo", "1.2.3", "native_demo", "auto", "3.12",
                "arm64-v8a", 26, recipes, metadata(),
            )

        self.assertEqual("generic-chaquopy", first["strategy"])
        self.assertEqual("a" * 64, first["source_sha256"])
        self.assertEqual(first["request_id"], second["request_id"])

    def test_auto_uses_matching_committed_adapter(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            recipes = Path(temporary)
            recipe = {
                "package": "native-demo",
                "version": "1.2.3",
                "module": "adapter_module",
                "python_version": "3.12",
                "android_abi": "arm64-v8a",
                "minimum_sdk": 26,
                "source_sha256": "b" * 64,
            }
            (recipes / "native-demo-1.2.3.json").write_text(json.dumps(recipe), encoding="utf-8")
            result = resolver.resolve_request(
                "native-demo", "1.2.3", "ignored_module", "auto", "3.12",
                "arm64-v8a", 26, recipes, metadata(digest="b" * 64),
            )

        self.assertEqual("adapter", result["strategy"])
        self.assertEqual("adapter_module", result["module"])
        self.assertTrue(result["adapter"].endswith("native-demo-1.2.3.json"))

    def test_rejects_non_pypi_source(self) -> None:
        value = metadata()
        value["urls"][0]["url"] = "https://example.invalid/source.tar.gz"
        with self.assertRaisesRegex(ValueError, "hash-pinned source distribution"):
            resolver.select_sdist(value)


if __name__ == "__main__":
    unittest.main()
