# New Feature Backlog

This file defines the current meaning of `okay add all new features`.

When that phrase is used, the implementation target is the full unchecked backlog in this file, unless we replace or edit it first.

## Current Baseline In Repo

- Client launcher with Microsoft and offline login
- Vanilla client install and launch flow
- Fabric client install flow
- Vanilla dedicated server download and launch flow
- Custom version support

## Requested Upgrade Set

### 1. Modloader Expansion

- [ ] Refactor install/launch handling around a shared profile model with:
  - side: `client` or `server`
  - loader family: `vanilla`, `fabric`, `quilt`, `forge`, `neoforge`
  - Minecraft version
  - loader version
  - install directory and metadata
- [ ] Replace the Fabric-only install dialog with a general modloader install flow.
- [ ] Support client installs for `Fabric`, `Quilt`, `Forge`, and `NeoForge`.
- [ ] Support server installs for `Fabric`, `Quilt`, `Forge`, and `NeoForge`.
- [ ] Keep vanilla client/server installs working through the same UI model.
- [ ] Persist profile metadata so installed loaders show up correctly in both the client and server tabs.

### 2. Mod Browser / Web Preview

- [ ] Add an embedded web preview window for browsing mods inside Limecraft.
- [ ] Let the user choose whether the selected mod is for the current `client` profile or `server` profile.
- [ ] Filter the browser by Minecraft version, loader family, and side compatibility.
- [ ] Start with `Modrinth` as the primary source for search and downloads.
- [ ] Add download/install actions from the preview window into the correct profile `mods` folder.
- [ ] Show mod details before install: name, author, version, supported game versions, supported loaders, side support, and dependencies when available.

### 3. Version-Scoped Mod / Content Safety

- [x] Keep client mods/content in per-version instance folders instead of forcing one shared workspace.
- [ ] Warn before launch when the selected version's mods folder contains obviously incompatible jars for the selected loader or Minecraft version.
- [ ] Warn when client-only mods are about to be used on a server flow, or server-only mods on a client flow.
- [ ] Add dependency-aware install/update checks during mod downloads instead of building a full per-profile mod manager.
- [ ] Add lightweight duplicate/conflict detection for jars in the selected version's mods folder.

### 4. Server-Specific Improvements

- [ ] Add server profile install presets for the supported loaders instead of vanilla-only server setup.
- [ ] Keep server launch settings per server profile.
- [ ] Add a one-click "sync from client profile" flow that copies only server-valid mods/config where possible.
- [ ] Expose loader-aware server bootstrap handling so Forge/NeoForge server start scripts and installer outputs are supported instead of assuming a single `server.jar`.

### 5. Quality-Of-Life Upgrades

- [ ] Improve status/progress reporting for loader installs and mod downloads.
- [ ] Cache loader/version manifests to reduce repeated network calls.
- [ ] Add retry/error details for failed downloads.
- [ ] Add profile badges in the UI so it is obvious whether an entry is `vanilla`, `fabric`, `quilt`, `forge`, or `neoforge`, and whether it is `client` or `server`.
- [ ] Add import/export for profile manifests so modded setups can be shared.

### 6. Core Launcher Gaps Found In Code Audit

- [ ] Add a real account manager with support for multiple saved Microsoft accounts and fast account switching.
- [ ] Move Microsoft refresh token storage out of plain `launcher.properties` and into secure OS-backed credential storage.
- [ ] Add sign-out/remove-account flows per saved account.
- [ ] Persist server profile settings per server instead of keeping them as session-only UI fields.
- [ ] Load existing server settings back into the server UI when a server profile is selected.
- [ ] Stop rewriting only a narrow subset of `server.properties`; preserve unknown keys and user edits when updating server config.
- [ ] Add repair/verify/redownload tools for versions, libraries, assets, and server files.
- [ ] Add retry, cancel, and queued job handling for installs/downloads instead of only one-shot background tasks.
- [ ] Expand the profile model beyond `id/type/url/releaseTime` so profiles can carry richer metadata.
- [ ] Add profile metadata such as icon, notes, tags, favorite state, grouping, and playtime tracking.
- [x] Keep client data in per-version instance folders instead of forcing a shared client workspace.
- [x] Keep manual custom-version creation/editing available through `Add Version`.
- [ ] Finish removing stale shared-workspace wording and dead code paths.
- [ ] Add backup/snapshot/restore flows before destructive actions like deleting versions, changing loaders, or moving worlds.
- [ ] Add crash-report and log tooling, including quick-open actions for logs/crash reports and basic diagnosis of likely failures.
- [ ] Upgrade Java runtime management beyond scanning `C:/Program Files/Java`, including better discovery, version matching, and optional runtime download/install flows.
- [x] Block client/server launches when the selected Java runtime is older than the Minecraft metadata requires.
- [ ] Add embedded web support infrastructure (`javafx.web`) so in-app web preview/news/browser features are actually possible.
- [ ] Refactor the oversized `LimecraftApp` into smaller UI/service components before piling on major new features.

### 7. Storage / Shell UX / Self-Update

- [x] Store all Limecraft launcher data in `%USERPROFILE%/.limecraft`.
- [x] Restore missing files from the deprecated portable `Limecraft-data` / `data` folders into `%USERPROFILE%/.limecraft` without overwriting existing `.limecraft` files.
- [x] Add a custom undecorated window topbar with:
  - app title/branding
  - minimize button
  - maximize/fullscreen toggle
  - close button
- [x] Move the update indicator into the custom topbar instead of burying it elsewhere in the UI.
- [x] Check for launcher updates on startup.
- [x] When an update is available, show a topbar icon/button with update state and target version.
- [x] Use safe update staging:
  - download updates into a temp sibling folder
  - verify the downloaded package before swap
  - only replace the live packaged app after verification succeeds
- [x] Add a self-update flow that:
  - closes Limecraft
  - downloads the new packaged build
  - replaces the current packaged app image
  - relaunches the updated app
- [x] Treat the updater as app-image replacement, not lone-`exe` replacement, because the current Windows package depends on `app/` and `runtime/`.
- [x] Add topbar status icons for:
  - update available
  - downloads/jobs running
  - current launcher/account state
  - recent launcher error state

### 8. Diagnostics / Suggested Fixes

- [x] Keep dumping the real raw output/log text for failed installs and launches.
- [x] Add a structured "Suggested Fix" panel under failures instead of only showing raw logs.
- [x] Reuse existing loader-specific diagnostics where they already exist instead of duplicating weaker generic guesses.
- [x] Add rule-based suggestions for common problems:
  - wrong Java version
  - wrong loader family
  - wrong Minecraft version
  - missing dependency
  - client-only mod on server
  - server-only mod on client
  - broken/incomplete download
- [x] Make install/launch error dialogs include:
  - short summary
  - likely cause
  - suggested fix
  - button to open the relevant log/crash file
- [x] When a launcher-side error occurs, show a dedicated "Report Launcher Error" action/button.
- [x] Make the report action optional and link directly to the GitHub problems/issues page for the repo.
- [x] Include enough context for manual reporting:
  - launcher version
  - active profile/version if relevant
  - short error summary
  - easy path to logs/crash files

### 9. Release Automation

- [x] Keep release automation as a repo-local batch script, not a separate external toolchain requirement.
- [x] Add a dedicated release batch script that:
  - builds/package the Windows app image
  - creates the release zip
  - verifies the packaged output is present
  - can tag/push/upload a GitHub release when credentials are available
- [x] Make the release script print the full command output and final artifact paths clearly.
- [x] Make the release script fail loudly with actionable suggestions instead of just stopping on errors.
- [x] Add release sanity checks before upload:
  - no `launcher.properties`
  - no saved tokens/account cache
  - no obvious local path/user-name leakage in packaged config files

### 10. Codebase Separation

- [x] Split `LimecraftApp` into smaller modules instead of continuing to grow a monolith.
- [x] Extract a launcher shell/window module for:
  - topbar
  - tab host
  - shared status/progress
  - update indicator
- [ ] Extract client-profile UI/controller code from server-profile UI/controller code.
- [ ] Extract mod browser/mod management UI into its own package.
- [ ] Extract install/update/download orchestration into dedicated services.
- [x] Extract settings/account/update/release concerns into separate services/controllers.
- [ ] Keep the refactor incremental so the app still builds and ships after each slice.

## Assumptions For "All Modloaders"

Current assumed baseline for "all modloaders" means the major modern Java loader families:

- `Fabric`
- `Quilt`
- `Forge`
- `NeoForge`

Vanilla remains included for both client and server.

Long-tail or legacy loader families are not fully defined yet. Candidates if we decide to widen scope later:

- `Legacy Fabric`
- `LiteLoader`
- `Rift`
- plugin/server ecosystems like `Paper`, `Purpur`, `Folia`, `Spigot`, `Bukkit`

## Missing Decisions

These are the parts still not fully defined yet:

- Exact definition of "all modloaders" beyond the baseline above
- Whether plugin server platforms count as part of the same feature set
- Whether CurseForge should be included alongside Modrinth
- Whether the mod browser should be a pure embedded web page, a launcher-native UI backed by web APIs, or both
- Per-version instance folders are the client mode; shared client workspace support was intentionally removed.
- Whether multi-account support should include offline account presets too
- Whether runtime download/install should be fully launcher-managed or only help the user find the right Java
- Whether the old deprecated portable folders should eventually be auto-deleted after their missing files are restored into `%USERPROFILE%/.limecraft`
- Whether self-update should pull from GitHub Releases directly or from a separate update manifest
- Whether the custom topbar should mimic native Windows behavior or deliberately use a branded launcher style

## Recommended Implementation Order

1. Generalize the current Fabric-only model into a loader-agnostic profile/install system.
2. Add Forge, NeoForge, and Quilt installers for client and server.
3. Make server launching loader-aware instead of assuming one vanilla `server.jar`.
4. Add secure account storage/multi-account support and persist real server profiles.
5. Add repair/verify/backup/runtime-management foundations.
6. Split `LimecraftApp` into smaller components before the next big UI wave.
7. Add the custom topbar + startup update indicator + fixed `.limecraft` storage model.
8. Add the embedded mod browser and install flow.
9. Add per-version compatibility checks, dependency-aware installs, sync, updates, and sharing features.
10. Add self-update + release-batch automation once packaging/layout conventions are stable.
