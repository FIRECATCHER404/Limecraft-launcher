# Limecraft Launcher

Limecraft is a Minecraft: Java Edition launcher for Windows that combines client launching, server launching, modloader installation, mod browsing, account management, repair tools, crash diagnostics, and self-updates in one app.

The launcher is designed around one shared Limecraft data folder and per-version instance folders. All launcher data is stored in `%USERPROFILE%/.limecraft`. Each Minecraft version uses its own folder under `%USERPROFILE%/.limecraft/instances/<version>` for saves, logs, crash reports, mods, config, and options, while libraries, assets, accounts, and version metadata are shared by the launcher.

## Download

Download the latest packaged build from the GitHub Releases page, extract the zip, and run `Limecraft.exe` inside the extracted `Limecraft` folder.

Packaged and development builds store launcher data in `%USERPROFILE%/.limecraft`. Version `1.6` restores this behavior after `1.5` incorrectly used a portable sibling data folder. On startup, Limecraft will copy missing files from the old `Limecraft-data` or `data` folder into `%USERPROFILE%/.limecraft` if one of those deprecated folders exists.

## Core Features

- Launches official Minecraft releases, snapshots, experiments, legacy versions, custom versions, and installed modloader versions.
- Shows all known Minecraft versions plus added custom/modded versions, including installed-state indicators.
- Stores all launcher data in `%USERPROFILE%/.limecraft`.
- Uses per-version instance folders under `%USERPROFILE%/.limecraft/instances`, so saves, logs, crash reports, mods, and options stay separated by Minecraft version.
- Supports Microsoft device-code login, saved Microsoft accounts, secure token storage through Windows DPAPI, saved-account restore, sign out, and offline mode.
- Automatically downloads Mojang client jars, libraries, natives, and assets when needed.
- Reuses valid cached libraries/assets/jars/natives instead of redownloading everything every launch.
- Verifies Mojang hashes and sizes where metadata provides them.
- Supports version inheritance, legacy argument formats, native extraction, old applet-era launch handling, and main-class inference.
- Provides custom Java path and max-memory settings, including per-version overrides.
- Detects installed Java runtimes from common install locations, Minecraft runtimes, `JAVA_HOME`, and `PATH`.
- Recommends a Java major version for the selected Minecraft version when possible.
- Includes launch logs, repair tools, recent versions, version search, experimental version visibility, crash-report shortcuts, and instance folder shortcuts.

## Client Tools

- Launches the selected client version from its own `instances/<version>` game directory.
- Supports custom versions and modloader versions alongside official Mojang versions.
- Provides instance settings for Java override, memory override, notes, favorites, tags/groups, icon path metadata, and display behavior.
- Opens the selected instance folder, worlds folder, logs folder, and crash-reports folder.
- Creates instance snapshots/backups.
- Transfers worlds between version instance folders.
- Diagnoses recent crash reports and surfaces likely causes or fixes.
- Can terminate the launched Minecraft process tree from the launcher.
- Batches noisy Minecraft log output so the launcher UI stays responsive.

## Modloader Support

Limecraft can install modloaders for both client and server profiles:

- Fabric
- Quilt
- Forge
- NeoForge

The Install Modloader window supports Minecraft version selection, loader selection, side selection, and loader-version dropdowns so loader versions do not have to be typed manually. Manual loader-version entry is still available when needed.

Fabric and Quilt installs use metadata profiles/server jars. Forge and NeoForge installs run their official installer jars for client and server setup.

## Mod Browser

The built-in mod browser uses Modrinth and is enabled only when the selected profile is a supported modloader profile.

- Filters mods by the selected Minecraft version, modloader, and client/server side.
- Disables browsing for vanilla profiles so incompatible mods are not installed into vanilla versions.
- Shows project details in a preview pane.
- Shows only supported release Minecraft versions by default.
- Includes a Show All Versions toggle to include snapshots and other supported versions.
- Lets you choose the supported Minecraft version and exact mod file/version before installing.
- Installs mods into the selected profile's `mods` folder.
- Sanitizes downloaded filenames so mod files cannot escape the target mods folder.
- Includes shortcuts to open the mods folder and the Modrinth page.

## Server Launcher

Limecraft has a dedicated Server tab for creating and launching Minecraft servers.

- Launches vanilla dedicated servers and modloader server profiles.
- Downloads vanilla server jars when needed.
- Installs and launches Fabric, Quilt, Forge, and NeoForge servers.
- Saves server settings per server profile.
- Supports Java path, RAM, port, MOTD, max players, online mode, PVP, command blocks, and no-GUI settings.
- Merges launcher settings into `server.properties`.
- Can reuse the selected client Java path.
- Provides a server console with command input.
- Supports server repair actions.
- Supports server-side mod browsing and installation.
- Can terminate the launched server process tree from the launcher.

## UI and Updates

- Uses a custom dark UI with Client and Server tabs.
- Includes a custom topbar with minimize, maximize, and close buttons.
- Maximize behaves like normal window maximize, not exclusive fullscreen.
- Supports window resizing in packaged builds.
- Shows topbar status chips for update availability, active jobs, errors/reporting, and account state.
- Provides error reporting shortcuts that open the GitHub issues page and copy useful error details when available.
- Checks GitHub Releases for updates on startup.
- Packaged builds can self-update by downloading the release zip, staging it in a sibling temp folder, validating the app structure, swapping folders, and reopening the launcher.

## Packaging and Development

- `launch.bat` runs the launcher from source/development output.
- `ship.bat` builds the packaged Windows app image.
- `release.bat` builds the distributable zip, runs a privacy/leak scan, prints the package hash, and can use GitHub CLI for uploads when `gh` is installed.
- Release packages are meant to be extracted as a folder and run from the included `Limecraft.exe`.
- Extracting the app anywhere is fine; runtime data still goes to `%USERPROFILE%/.limecraft`, not beside the executable.

## Privacy and Safety Notes

- Release packaging blocks known private runtime files such as saved accounts, secure tokens, launcher properties, and profile data from being included in release zips.
- Microsoft refresh tokens are stored with Windows DPAPI-backed secure token storage.
- Mod downloads are restricted to the selected profile's `mods` folder through filename sanitization.
- Native extraction blocks zip path traversal.
- Updates are staged and app-structure validated before replacement.

## Current Limits

- CurseForge browsing is not included yet.
- Mod dependency auto-install is not finished yet; dependencies may be shown, but they are not automatically resolved.
- Java runtime download/install is not included yet; Limecraft detects and selects Java runtimes already installed on the system.
- Update packages are structure-validated, but not yet signed with a separate release manifest.
- Paper, Purpur, Folia, Spigot, and Bukkit server platform installers are not included yet.
- Icon metadata exists, but full icon rendering in version lists is not finished yet.

## Screenshots

<img width="1919" height="1008" alt="Limecraft client tab" src="https://github.com/user-attachments/assets/b04fffbb-d70d-4473-a3fc-72846c15f8e4" />

<img width="1919" height="1004" alt="Limecraft launcher UI" src="https://github.com/user-attachments/assets/7416dce3-9065-4899-ab05-ab01e3f9b83b" />

<img width="1919" height="1006" alt="Limecraft screenshot" src="https://github.com/user-attachments/assets/2f332f25-0c84-4395-9060-fa35ac5ade6d" />

<img width="1919" height="1003" alt="Limecraft server tab" src="https://github.com/user-attachments/assets/817247b7-79b5-4108-a08f-591fff65b006" />

<img width="751" height="268" alt="Limecraft modloader install window" src="https://github.com/user-attachments/assets/e0fe4adf-5a35-44d6-9469-f79a6ff216f5" />
