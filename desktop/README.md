# Archipelago Companion for Windows and Linux

This is the native desktop frontend for the Android Archipelago Companion. It
runs the checkout's normal Archipelago Python generator and desktop clients;
there is no Android emulation layer and no Chaquopy runtime.

## Current features

- Resizable high-DPI Windows/Linux interface with overview, room library,
  generator, APWorld manager, client console, and application settings.
- Persistent rooms with game, server, player, password, and player-patch data.
- Standard Archipelago `archipelago://` handoff, including automatic selection
  of any installed game-specific client which advertises URI support.
- Player patch handling through the upstream Archipelago Launcher.
- Seed generation in an isolated worker process using one or more saved YAMLs.
- APWorld installation through Archipelago's standard desktop installer.
- PopTracker, RetroArch, and Dolphin executable configuration. PopTracker is
  launched with the active room's matching pack, server, player, and password.
- Complete desktop Companion backup and restore.
- GitHub release checks.

The desktop data directory is `%LOCALAPPDATA%/Archipelago Companion` on Windows
and `$XDG_DATA_HOME/archipelago-companion` (normally
`~/.local/share/archipelago-companion`) on Linux. Set
`ARCHIPELAGO_COMPANION_HOME` to override it. Passwords are currently stored in
the local state file with the same confidentiality as the user's OS account;
do not share that file or a Companion backup.

## Run from source

Use Python 3.12 from the repository root. The bootstrap creates an isolated
`.desktop-venv` containing the upstream and desktop dependencies:

```powershell
.\desktop\bootstrap-windows.ps1
.\desktop\run-desktop.ps1
```

On Linux:

```sh
sh desktop/bootstrap-linux.sh
sh desktop/run-desktop.sh
```

Installed custom APWorlds and their native dependencies remain the
responsibility of the standard Archipelago desktop environment. This is
intentional: it preserves upstream client and generator behavior instead of
passing them through the Android compatibility adapters.

## Verification

The desktop core and Qt startup/layout smoke tests run on both
`windows-latest` and `ubuntu-latest` in GitHub Actions. Locally:

```powershell
$env:PYTHONPATH = "desktop"
python -m unittest discover desktop/tests -v
```

The repository's existing `setup.py build_exe --yes` packaging path includes
`ArchipelagoCompanion.exe` in Windows distributions and
`ArchipelagoCompanion` in Linux distributions. The main Archipelago Launcher
also exposes it as **Companion**. Frozen builds call the sibling Launcher,
Generator, and Text Client executables, so they do not depend on a source tree
or an external Python interpreter at runtime.
