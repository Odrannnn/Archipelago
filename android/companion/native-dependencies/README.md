# Android Python dependencies

This directory builds native Python artifacts used by imported APWorlds. The published catalog is a verified build cache, not an APWorld or dependency allowlist. The companion discovers requirements from the APWorld the user chose to trust, installs universal wheels directly from PyPI, and looks up native artifacts by normalized package name and compatible version.

The manual `Android Python dependencies` workflow currently accepts build adapters committed under `recipes/`. This restriction protects GitHub runner capacity and expresses Android cross-compilation details; it does not authorize which APWorld may use the resulting package. A build packages one Android Python module, attests it, and can update the long-lived `android-python-dependencies` GitHub release. The companion obtains both the cache index and package digests from GitHub's release API before installing anything in app-private storage.

New native packages still need a compatible cross-build adapter until a sandboxed public build-request service exists. Do not add arbitrary shell fragments to recipes. Pure-Python `py3-none-any` wheels require no recipe or catalog entry.

Current build kinds:

- `cargo-pyo3`: Rust/PyO3 extension with a Python package wrapper.

Publishing is permitted only when the workflow runs from `main`. Pull requests and ordinary pushes build and validate recipes without modifying releases.
