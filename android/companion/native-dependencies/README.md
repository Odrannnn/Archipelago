# Android Python dependencies

This directory builds native Python artifacts used by imported APWorlds. The published catalog is a verified build cache, not an APWorld or dependency allowlist. The companion discovers requirements from the APWorld the user chose to trust, installs universal wheels directly from PyPI, and looks up native artifacts by normalized package name and compatible version.

The manual `Android Python dependencies` workflow accepts an exact PyPI package, version, and import module. It resolves the source distribution through PyPI's JSON API, pins its SHA-256, and derives a stable request ID before running any package build code. `auto` selects a committed adapter when one exactly matches the package and version, and otherwise attempts a generic PEP 517 cross-build. A build packages one Android Python module, validates it without importing it, attests it, and can update the long-lived `android-python-dependencies` GitHub release. The companion obtains both the cache index and package digests from GitHub's release API before installing anything in app-private storage.

The companion currently embeds CPython 3.12, so generic builds use Chaquopy's pinned CPython 3.12 package builder rather than current `cibuildwheel`, whose standard Android targets begin at CPython 3.13. Generic builds work for ordinary PEP 517 projects which honor the Android compiler environment. Packages with unusual toolchains, target-side build requirements, patches, or old Rust/PyO3 behavior still need a compatible adapter under `recipes/`. Do not add arbitrary shell fragments to recipes. Pure-Python `py3-none-any` wheels require no remote build or catalog entry.

Current build kinds:

- `cargo-pyo3`: Rust/PyO3 extension with a Python package wrapper.

Publishing is permitted only when the workflow runs from `main`. Pull requests and ordinary pushes build and validate recipes without modifying releases.

## Manual build

Open **Actions → Android Python dependencies → Run workflow**, then enter:

- the exact PyPI distribution name and version declared by the APWorld;
- the top-level Python module which must exist in the result;
- `auto` unless testing the generic path or a committed adapter specifically;
- `publish: false` for the first build.

The workflow artifact contains `build-request.json`, the dependency ZIP, its catalog entry, and `validation-report.json`. Inspect the log and artifact first, then rerun from `main` with `publish: true` to merge the verified entry into the dependency-cache release.

Package source and build-system dependencies execute arbitrary code in the build job. The workflow therefore accepts only exact PyPI releases, limits source and artifact sizes, rejects unsafe archive paths and symlinks, and pins the Chaquopy checkout. A future application-facing broker must add authentication, request deduplication, quotas, and time limits before exposing this workflow to devices.
