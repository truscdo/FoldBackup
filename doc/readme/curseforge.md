# FoldBackup

FoldBackup is a world backup tool for Minecraft NeoForge servers. It supports automatic and manual **snapshot backups**, **archive compression**, **historical chunk previews**, and **chunk rollback**.

Snapshots use a Git-like content deduplication scheme: each backup records only new and changed files. Region files are stored in LRH format. On a Minecraft 1.21.1 NeoForge survival server with more than 80 mods, an archive created at compression level 19 can be as small as one-third of the original world size.

The same JAR can be installed as a NeoForge mod or run as a standalone tool. It provides a command-line interface (CLI) and an interactive menu (TUI), so you can use its features while the server is offline.

- **Environment**: Minecraft `1.21.1`, NeoForge `21.1+`, Java `21+` (the features provided by standalone CLI / TUI mode are not tied to a Minecraft version and are compatible with most Minecraft versions)
- **Installation**: Only required on the **server** (or singleplayer client); players connecting to the server do **not** need to install this mod on their clients to use all features (including in-game historical chunk previews)

---

## Features

- **Online backups**: Schedule backups based on player online time, trigger them manually, or optionally back up the world when the server starts or stops.
- **Offline backups**: Back up worlds using the TUI or CLI without starting the game.
- **Content deduplication**: Unchanged content takes no additional space in a new snapshot.
- **Snapshot management**: Inspect repository space summaries and each snapshot's unique, shared, and cumulative reclaimable space, pin important snapshots to protect them from automatic cleanup, estimate space reclaimed before batch deletion, and configure automatic cleanup.
- **Chunk previews and rollback**: Preview how a chunk looked in an earlier snapshot and restore a single chunk from a selected snapshot into the world; roll back chunks directly in a save using the CLI without starting the game, helping resolve problematic chunks or crashes caused by too many entities.
- **Fast full rollback**: When exporting a snapshot as a Minecraft world, if a world save already exists at the target location, unchanged parts are skipped to speed up the rollback process.
- **Archive compression**: Create high-compression full or differential archives for distribution or storage, and restore them as a Minecraft world or import them into a snapshot repository; for servers, differential archives let you transfer only the changed parts of the world so you can edit the map locally.

> **Note — Difference between Snapshots and Archives**
>
> - **Snapshots**: Frequent automatic or manual backups stored with incremental deduplication in a local repository, used for quick rollback, world export, and single-chunk recovery.
> - **Archives**: Standalone files optimized for high compression ratios (supporting full and differential archives), suitable for off-site cold backups, cross-machine syncing, or world distribution.

### LRH and Linear

**LRH is a streaming implementation based on the Linear region format** and is used to compress and store Region data in FoldBackup. The original project is [xymb-endcrystalme/LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools). Thanks to its authors for the format design and implementation.

FoldBackup also introduces the LRHD format to record chunk differences in archives, enabling chunk-level differential backups and storage.

---

## Quick Start

### Create a Backup

1. Place the FoldBackup JAR in the NeoForge server's `mods/` directory.
2. Start the server and wait for the world to load and FoldBackup to initialize.
3. In game, run the following commands with permission level 2 or higher:

   ```text
   /foldbackup backup --force
   /foldbackup list
   ```

   In singleplayer, the world host can use these commands even when cheats are off. Other players joining over LAN cannot use them.

By default, backups are stored under `foldbackups/` in the server's working directory and can be changed with `general.backupPath`. A dedicated server uses this directory directly as the repository, while singleplayer uses `foldbackups/<world-folder-name>` so each world keeps its own repository.

Automatic backups are enabled by default. The interval counts accumulated time while players are online. Manual backups are also subject to the minimum interval between backups; use `/foldbackup backup --force` to bypass it.

Even without installing the mod in game, you can create snapshots offline for any version of a Minecraft world using the standalone CLI or TUI:

**Offline backup instructions (CLI / TUI)**:

<div class="spoiler">

> Before running the TUI or CLI, you must first export the bundled runtime libraries from the JAR (this design avoids dependency conflicts between the mod and the CLI), and then run the TUI or CLI (you can skip this step if you use the TUI quick-start script).
>
> ```bash
> # Dedicated server: foldbackups/
> java -jar ../mods/foldbackup-<version>.jar extract
>
> # Singleplayer: foldbackups/<world-folder-name>
> java -jar ../../mods/foldbackup-<version>.jar extract
> ```
>
> By default, commands assume the current directory is the target world's repository directory (`foldbackups/` on a dedicated server, `foldbackups/<world-folder-name>` in singleplayer). If you run them from a different location, adjust the corresponding arguments.

**You must stop the server or exit the singleplayer world before creating an offline backup.**

**Create a snapshot with the CLI:**

```bash
# Create a snapshot using the default filter rules
java -jar ../mods/foldbackup-<version>.jar snapshot create --world ../world

# Append extra exclude rules (quote patterns containing * or ** in your shell to prevent premature expansion)
java -jar ../mods/foldbackup-<version>.jar snapshot create --world ../world \
  --exclude "**/DistantHorizons.sqlite" \
  --exclude "cache/"
```

- `--world <directory>`: Required; points to the root directory of the world save.
- `--exclude <glob>`: Optional and repeatable; appends exclude patterns on top of the default filter rules.

**Create a snapshot with the TUI:**

Run `java -jar ../mods/foldbackup-<version>.jar tui` (or the launcher script) in the repository directory, select **`6. Create snapshot from world`**, and follow the prompts:

1. **World directory**: Enter the path to the world save root directory (press Enter to return to the main menu).
2. **Extra exclude rules**: The screen lists the default protection rules first; to append more, enter multiple comma-separated patterns on a single line (`{a,b}` is supported, e.g., `**/DistantHorizons.sqlite, cache/`). Press Enter to add none, or enter `0` to go back; invalid rules prompt you to re-enter in place.
3. **Thread count**: Enter a positive integer, press Enter to choose automatically, or enter `0` to go back.

**File filter rules:**

- Offline snapshot creation always keeps 4 core default rules: `**/session.lock`, `**/backups`, `**/.backups`, and `**/*.wip` (unlike the mod's default configuration, offline mode does not exclude `**/DistantHorizons.sqlite` by default; append it manually as shown above if needed).
- Patterns match paths relative to the world directory using `/` as the separator and **must not start with `/`** (for example, write `backups/` instead of `/backups/`); `*`, `**`, `?`, `[...]`, and `{a,b}` are supported.
- Matching a directory skips its entire subtree; a pattern ending with `/` (such as `cache/`) matches directories only, not files with the same name. Empty rules, leading `/`, or invalid glob syntax abort immediately with an error.

</div>

### Quick Start the TUI

If you prefer a launcher, save one of the scripts below into the repository directory as `foldbackup-tui.bat` or `foldbackup-tui.sh` (then `chmod +x` it on Linux). The script looks for the JAR beside itself, then in `../mods`, then in `../../mods`, and automatically exports the bundled runtime libraries.

**Windows launcher script (`foldbackup-tui.bat`)**:

<div class="spoiler">

```bat
@echo off
setlocal DisableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
set "JAR="
call :find "%SCRIPT_DIR%."
call :find "%SCRIPT_DIR%..\mods"
call :find "%SCRIPT_DIR%..\..\mods"
if not defined JAR (
    echo Error: foldbackup jar not found beside this script, in ..\mods, or in ..\..\mods.
    pause
    exit /b 1
)
java -jar "%JAR%" extract --output "%SCRIPT_DIR%.foldbackup-cli"
if errorlevel 1 (
    echo Error: failed to export bundled libraries ^(read-only repository directory?^).
    pause
    exit /b 1
)
java -jar "%JAR%" tui --snapshot-root "%SCRIPT_DIR%." --archive-root "%SCRIPT_DIR%." --lib "%SCRIPT_DIR%.foldbackup-cli"
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" pause
exit /b %RESULT%

:find
if defined JAR exit /b 0
if exist "%~1\foldbackup.jar" set "JAR=%~1\foldbackup.jar"
if defined JAR exit /b 0
for %%f in ("%~1\foldbackup-*.jar") do if not defined JAR if exist "%%~ff" set "JAR=%%~ff"
exit /b 0
```

</div>

**Linux / macOS launcher script (`foldbackup-tui.sh`)**:

<div class="spoiler">

```sh
#!/bin/sh
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=
for dir in "$SCRIPT_DIR" "$SCRIPT_DIR/../mods" "$SCRIPT_DIR/../../mods"; do
    if [ -f "$dir/foldbackup.jar" ]; then
        JAR="$dir/foldbackup.jar"
        break
    fi
    for candidate in "$dir"/foldbackup-*.jar; do
        if [ -f "$candidate" ]; then
            JAR="$candidate"
            break
        fi
    done
    if [ -n "$JAR" ]; then
        break
    fi
done
if [ -z "$JAR" ]; then
    echo "Error: foldbackup jar not found beside this script, in ../mods, or in ../../mods." >&2
    exit 1
fi
java -jar "$JAR" extract --output "$SCRIPT_DIR/.foldbackup-cli" || {
    echo "Error: failed to export bundled libraries (read-only repository directory?)." >&2
    exit 1
}
exec java -jar "$JAR" tui --snapshot-root "$SCRIPT_DIR" --archive-root "$SCRIPT_DIR" --lib "$SCRIPT_DIR/.foldbackup-cli"
```

</div>

### Roll Back a Chunk

You can preview and roll back a single chunk in game, or use the CLI to roll back a chunk directly in an offline world.

In game, use `/foldbackup preview from <snapshot-id>` (defaults to the chunk you are standing in) or `/foldbackup preview from <snapshot-id> at <x> <z>` to preview a chunk from a snapshot. This changes how the target chunk appears on the client to show its state at the time of that snapshot; it does not modify the actual chunk. `x` and `z` are chunk coordinates. You can find them on the F3 screen, or divide the block coordinates by 16 and round down. A blue particle effect marks the previewed chunk. Use `/foldbackup preview exit` to end the preview.

To roll back a chunk in game, use `/foldbackup rollback chunk from <snapshot-id>` to restore the chunk you are currently in. To target specific coordinates, append `at <x> <z>` or `in <dimension> at <x> <z>` (required when running from the server console or RCON), and optionally narrow the scope with `blocks` (terrain, block entities, and POI only, preserving current entities) or `entities` (entities only, leaving terrain and block entities untouched): e.g. `/foldbackup rollback chunk blocks from <snapshot-id> at <x> <z>` or `/foldbackup rollback chunk entities from <snapshot-id> in <dimension> at <x> <z>`. The target chunk must already be loaded in memory. For an unloaded chunk, use the offline CLI method below:

**CLI offline rollback instructions**:

<div class="spoiler">

> Before running the TUI or CLI, you must first export the bundled runtime libraries from the JAR (this design avoids dependency conflicts between the mod and the CLI), and then run the TUI or CLI (you can skip this step if you use the TUI quick-start script).
>
> ```bash
> # Dedicated server: foldbackups/
> java -jar ../mods/foldbackup-<version>.jar extract
>
> # Singleplayer: foldbackups/<world-folder-name>
> java -jar ../../mods/foldbackup-<version>.jar extract
> ```
>
> By default, commands assume the current directory is the target world's repository directory (`foldbackups/` on a dedicated server, `foldbackups/<world-folder-name>` in singleplayer). If you run them from a different location, adjust the corresponding arguments.

**Stop the server before performing an offline rollback.**

List the snapshots:

```bash
java -jar ../mods/foldbackup-<version>.jar snapshot list
```

Copy the target snapshot ID from the list, then roll back the chunk. The following command restores chunk `[x, z]` in the Overworld directory `world` to its state in that snapshot:

```bash
java -jar ../mods/foldbackup-<version>.jar snapshot rollback <snapshot-id> --world ../world --chunk <chunk-x> <chunk-z> [--scope <all|blocks|entities>]
```

`--world` points to the target dimension directory: use the world root for the Overworld, `../world/DIM-1` for the Nether, `../world/DIM1` for the End, or the corresponding `dimensions/<namespace>/<path>` directory for a custom dimension. `--chunk` takes signed integer chunk coordinates, not block coordinates. `--scope` defaults to `all`, or can be set to `blocks` (terrain, block entities, and POI only) or `entities` (entities only). The CLI asks for confirmation before proceeding; append `--yes` to skip the prompt.

By default, the CLI restores the target chunk's terrain, block entities, recoverable entity data, and that chunk's points of interest exactly as recorded in the snapshot, including ticket occupancy. It does not restore player data or world-level configuration.

</div>

### Restore an Entire World

Due to performance reasons, in-game full world rollback is not currently supported, but you can restore an entire world using the TUI or CLI.

> Before running the TUI or CLI, you must first export the bundled runtime libraries from the JAR (this design avoids dependency conflicts between the mod and the CLI), and then run the TUI or CLI (you can skip this step if you use the TUI quick-start script).
>
> ```bash
> # Dedicated server: foldbackups/
> java -jar ../mods/foldbackup-<version>.jar extract
>
> # Singleplayer: foldbackups/<world-folder-name>
> java -jar ../../mods/foldbackup-<version>.jar extract
> ```
>
> By default, commands assume the current directory is the target world's repository directory (`foldbackups/` on a dedicated server, `foldbackups/<world-folder-name>` in singleplayer). If you run them from a different location, adjust the corresponding arguments.

```bash
# Dedicated server: foldbackups/
java -jar ../mods/foldbackup-<version>.jar tui

# Singleplayer: foldbackups/<world-folder-name>
java -jar ../../mods/foldbackup-<version>.jar tui
```

In the menu, select "2. Export world files" and follow the prompts to choose a snapshot, a directory or a ZIP, and the output path. Press Enter to accept the default path (`world-export/<snapshot-id>`, or the same name with `.zip` for a ZIP).

> **Important**: **Stop the server first** before exporting into an active world directory to restore it.

For a directory export, the target can be an existing game save: FoldBackup updates only the files that differ from the snapshot and skips identical files. A check is performed before exporting; after the check, choose "1 = overwrite" to keep files in the target that the snapshot does not contain, or "2 = delete extra" to remove them so the target matches the snapshot exactly. The thread-count prompt can be accepted with Enter.

The CLI can do the same export: `snapshot export <snapshot-id> --output <directory>`. `--zip` writes a ZIP; for a directory export, `--delete-extra` removes content that is not in the snapshot; `--dry-run` prints the statistics without writing or deleting.

### Full and Differential Archives

Archives are intended for storing and distributing worlds, with high compression ratios as a priority. Because creating them uses substantial resources, archive operations are available only through the CLI and TUI.

Archives are created from snapshots, so you need a snapshot before creating an archive. You can create one in game or through the TUI or CLI. In the TUI, select "5. Create archive", choose a snapshot and the archive type. A differential archive requires choosing an earlier snapshot to compare against, so it stores only what changed.

An archive can be restored as a Minecraft world ("7. Restore as world files" in the TUI) or imported as a snapshot into a snapshot repository. When restoring as a world, if a game save already exists at the target location, FoldBackup likewise updates it in place, and choosing "delete extra" removes files that are not in the archive. If you distribute a world using archives, you can package the mod and the TUI launcher script together (the script content is shown above).

The archive format is independent of the Minecraft version. You can use the CLI or TUI to compress worlds from versions that the mod does not support.

---

## Common In-Game Commands

| Command | Purpose |
| --- | --- |
| `/foldbackup backup` | Create a snapshot manually |
| `/foldbackup backup --force` | Create a backup without waiting for the minimum interval |
| `/foldbackup list [page]` | List snapshots (10 per page) with repository space summary (unique, shared, and cumulative reclaimable space) |
| `/foldbackup status` | Show task status |
| `/foldbackup info <snapshot-id>` | Show snapshot details and current unique/shared space breakdown |
| `/foldbackup delete [--dry-run] <id1> [id2 ...]` | Delete one or more snapshots, or estimate reclaimed space with `--dry-run` |
| `/foldbackup pin <snapshot-id>` | Pin a snapshot to protect it from automatic cleanup and manual deletion |
| `/foldbackup unpin <snapshot-id>` | Remove pin protection from a snapshot |
| `/foldbackup preview from <snapshot-id> [at <x> <z>]` | Preview a historical chunk in the current dimension (defaults to current chunk) |
| `/foldbackup preview exit` | End the preview |
| `/foldbackup rollback chunk from <snapshot-id> [at <x> <z>]` | Roll back a single chunk in the current dimension (defaults to current chunk) |
| `/foldbackup rollback chunk from <snapshot-id> in <dimension> at <x> <z>` | Roll back a single chunk in a specified dimension |
| `/foldbackup rollback chunk blocks from <snapshot-id> [in <dimension> at <x> <z> \| at <x> <z>]` | Roll back only blocks, block entities, and POI of a chunk (preserving current entities) |
| `/foldbackup rollback chunk entities from <snapshot-id> [in <dimension> at <x> <z> \| at <x> <z>]` | Roll back only entities of a chunk (leaving blocks and block entities untouched) |

**Snapshot list and space statistics guide & example (Mod / TUI / CLI)**:

<div class="spoiler">

The snapshot list is displayed **newest first**. The in-game mod displays a compact chat layout with the same English labels, while the TUI (`1. List snapshots`) and CLI (`snapshot list`) share the same full table output, with each metric corresponding one-to-one.

| Mod | TUI / CLI | Meaning |
| --- | --- | --- |
| `Stored` | `Stored` | Actual repository disk usage (deduplicated data blocks + `index.db` index file) |
| `Unique` / `Shared (All)` | `Unique` / `Shared [All]` | Space referenced by a single snapshot only / shared across multiple snapshots; `All` is baseline data referenced by every snapshot |
| `Pinned (Unique)` | `Pinned (Unique)` | Number of pinned snapshots, plus space held exclusively by the pinned snapshot set |
| — | `Logical (saved %)` | Total compressed snapshot size without cross-snapshot deduplication, and the space savings percentage |
| `Size` | `Size` | Compressed size of that snapshot itself (not equal to the space freed by deleting it) |
| `Unique` (in row) | `Unique` (column) | Space immediately freed by deleting that snapshot alone |
| `Reclaim` / `[PIN]` | `Reclaim` / `-` | Total space freed by deleting continuously from the oldest unpinned snapshot up to the current row in automatic cleanup order (skipping `[PIN]`) |

**TUI `1. List snapshots` (identical to CLI `snapshot list`) example:**

```text
Snapshots (5):
Stored: 601.0M (Unique: 350.0M + Shared: 250.0M [All: 50.0M], index: 1.0M) | Logical: 1000.0M (saved 40.0%) | Pinned: 1 (Unique: 80.0M)

#  ID                       Date                 Regions  Files    Size  Unique  Reclaim
----------------------------------------------------------------------------------------
1  S-20260901_120000        2026-09-01 12:00:00      145     88  230.0M  120.0M   410.0M
2  S-20260901_113000        2026-09-01 11:30:00      135     85  210.0M   40.0M   230.0M
3  S-20260901_110000 [PIN]  2026-09-01 11:00:00      125     82  190.0M   80.0M        -
4  S-20260901_103000        2026-09-01 10:30:00      125     82  190.0M   60.0M   190.0M
5  S-20260901_100000        2026-09-01 10:00:00      120     80  180.0M   50.0M    50.0M
```

- **Interaction and pagination**: The mod displays 10 entries per page; hovering over a snapshot row in chat shows its timestamp, region count, and extra file count, clicking it runs `/foldbackup info <snapshot-id>`, and a clickable pagination bar appears at the bottom when there are multiple pages. The TUI displays 15 entries per page and shows pagination key prompts at the bottom when there is more than 1 page (selection screens for export, delete, pin, and archive creation use the compact `# / ID / Date / Size / Unique` table).
- **Batch deletion estimate**: When you need to delete any combination of snapshots, use `/foldbackup delete --dry-run`, CLI `snapshot delete --dry-run`, or the TUI deletion confirmation screen to see the exact space reclaimed.

</div>

---

## Key Configuration Options

The configuration file is located at `config/foldbackup-server.toml` in the working directory by default. Names in the table use the form `group.key`.

| Option | Default | Description |
| --- | --- | --- |
| `general.enabled` | `true` | Enable automatic backups |
| `general.backupPath` | `./foldbackups` | Backup root relative to the server's working directory; absolute paths are also supported. A dedicated server uses it as the repository; singleplayer creates one repository per world folder under it |
| `general.compressionLevel` | `6` | Zstd compression level, from 1 to 22 |
| `scheduling.intervalMinutes` | `30` | Accumulated player online time between automatic backups, in minutes |
| `scheduling.minIntervalMinutes` | `10` | Minimum wall-clock time between backups, in minutes |
| `scheduling.savePauseStallSeconds` | `300` | Abort the backup and restore world auto-saving when the world scan makes no progress for this long; 0 disables the check |
| `scheduling.backupOnStart` | `false` | Back up on startup |
| `scheduling.backupOnStop` | `false` | Back up on shutdown |
| `retention.enabled` | `true` | Enable automatic cleanup |
| `retention.maxSizeGB` | `50` | Target repository size after cleanup, in GiB; 0 means unlimited |
| `retention.minFreeSpaceGB` | `5` | Target free space on the file system, in GiB; 0 disables this limit |
| `retention.maxAgeDays` | `0` | Maximum snapshot retention age, in days; 0 means unlimited |
| `performance.regionThreads` | `0` | Number of worker threads; 0 selects automatically |
| `performance.regionMemoryLimitMB` | `0` | Memory budget for streaming tasks; 0 selects automatically |
| `logging.progressLogIntervalSeconds` | `3` | Seconds between progress log messages (1 is most verbose, 300 is least) |
| `localization.defaultLanguage` | `en_us` | Language used when player language detection is disabled or the reported language is unsupported; one of `zh_cn`, `en_us`, `es_es`, `pt_br`, `ru_ru` |
| `localization.autoDetectPlayerLanguage` | `true` | Use each player's reported language when it is supported |

> **Warning**: **Automatic cleanup may delete all unpinned snapshots.** The default limits bound only repository size (50 GiB) and free space (5 GiB), so automatic cleanup can delete recent snapshots to satisfy those targets.
>
> Pin important snapshots with `/foldbackup pin <snapshot-id>` (or via CLI/TUI) to protect them, set all three cleanup limits to 0 to disable automatic deletion, or turn off automatic cleanup with `retention.enabled`.

`general.excludePatterns` matches paths relative to the world directory, using `/` as the path separator without a leading `/`. By default, it excludes `**/session.lock`, `**/backups`, `**/.backups`, `**/*.wip`, and `**/DistantHorizons.sqlite`. If a directory matches, its entire subtree is excluded, and a trailing `/` (such as `backups/`) matches directories only. For filtering options when creating offline snapshots via the CLI / TUI, see "Create a Backup" above.

---

## License and Acknowledgments

FoldBackup's project metadata declares the LGPL v3 license.

LRH is based on the Linear region format. The original project, [LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools), is licensed under BSD-2-Clause. Thanks to its original author and contributors.
