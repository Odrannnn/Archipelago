import json
import sys
import tempfile
import unittest
from pathlib import Path

import offline_generator


class AndroidNativeDependenciesTests(unittest.TestCase):
    def test_installed_site_packages_are_activated(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            site_packages = root / "python_dependencies" / "py-randomprime" / "1.31.1" / "site-packages"
            site_packages.mkdir(parents=True)
            registry = root / "python_dependencies" / "installed.json"
            registry.write_text(json.dumps([{
                "package": "py-randomprime",
                "version": "1.31.1",
                "site_packages": "py-randomprime/1.31.1/site-packages",
            }]), encoding="utf-8")

            path = str(site_packages.resolve())
            self.addCleanup(lambda: sys.path.remove(path) if path in sys.path else None)
            offline_generator._activate_android_dependencies(root)

            self.assertEqual(path, sys.path[0])

    def test_registry_cannot_escape_dependency_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            outside = root / "outside"
            outside.mkdir()
            dependency_root = root / "python_dependencies"
            dependency_root.mkdir()
            (dependency_root / "installed.json").write_text(json.dumps([{
                "site_packages": "../outside",
            }]), encoding="utf-8")

            path = str(outside.resolve())
            offline_generator._activate_android_dependencies(root)

            self.assertNotIn(path, sys.path)


if __name__ == "__main__":
    unittest.main()
