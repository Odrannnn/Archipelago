# Android Python dependencies

This directory contains reviewed build recipes for native Python dependencies used by imported APWorlds. Recipes pin the upstream source URL and SHA-256 digest; the Android workflow never accepts build commands from the app.

The manual `Android Python dependencies` workflow accepts only recipe identifiers committed under `recipes/`. A build packages one Android Python module, attests it, and can update the long-lived `android-python-dependencies` GitHub release. The companion obtains both the catalog and package digests from GitHub's release API before installing anything in app-private storage.

Adding a dependency requires a reviewed recipe and, when necessary, a new explicitly implemented build kind in `scripts/build_dependency.py`. Do not add arbitrary shell fragments to recipes.

Current build kinds:

- `cargo-pyo3`: Rust/PyO3 extension with a Python package wrapper.

Publishing is permitted only when the workflow runs from `main`. Pull requests and ordinary pushes build and validate recipes without modifying releases.
