from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


PYTHON_SOURCE = Path(__file__).resolve().parents[2] / "main" / "python"
SPEC = importlib.util.spec_from_file_location(
    "apworld_dependencies",
    PYTHON_SOURCE / "apworld_dependencies.py",
)
DEPENDENCIES = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(DEPENDENCIES)


class ApWorldDependenciesTest(unittest.TestCase):
    def test_scans_standard_requirements_and_desktop_lib_table(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory)
            (package / "requirements.txt").write_text(
                "schema==0.7.8\nignored; python_version < '3'\n",
                encoding="utf-8",
            )
            (package / "PrimeUtils.py").write_text(
                "LIBS = {\n"
                " 'py_randomprime': {\n"
                "  'version': '1.31.1',\n"
                "  'links': {'linux': 'https://files.pythonhosted.org/example.whl'},\n"
                " },\n"
                " 'ppc_asm': {\n"
                "  'version': '1.9.0',\n"
                "  'links': {system: 'https://files.pythonhosted.org/ppc.whl' for system in ['linux']},\n"
                " },\n"
                "}\n",
                encoding="utf-8",
            )

            found = {item["package"]: item for item in json.loads(DEPENDENCIES.scan_package(str(package)))}

            self.assertEqual("schema==0.7.8", found["schema"]["requirement"])
            self.assertEqual("py_randomprime", found["py-randomprime"]["module"])
            self.assertEqual("1.31.1", found["py-randomprime"]["version"])
            self.assertEqual("1.9.0", found["ppc-asm"]["version"])
            self.assertNotIn("ignored", found)

    def test_ignores_unrelated_literal_dictionaries(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory)
            (package / "Options.py").write_text(
                "VALUES = {'thing': {'version': '1.0', 'label': 'not a dependency'}}\n",
                encoding="utf-8",
            )

            self.assertEqual([], json.loads(DEPENDENCIES.scan_package(str(package))))
