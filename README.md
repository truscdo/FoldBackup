# FoldBackup

FoldBackup is a world backup tool for Minecraft NeoForge servers. It supports automatic and manual snapshot backups, archive compression, historical chunk previews, and chunk rollback.

Snapshots use a Git-like content deduplication scheme: each backup records only new and changed files. Region files are stored in LRH format. On a Minecraft 1.21.1 NeoForge survival server with more than 80 mods, an archive created at compression level 19 can be as small as one-third of the original world size.

The same JAR can be installed as a NeoForge mod or run as a standalone tool. It provides a command-line interface (CLI) and an interactive menu (TUI), so you can use its features while the server is offline.

## Features

- **Online backups**: Schedule backups based on player online time, trigger them manually, or optionally back up the world when the server starts or stops.
- **Content deduplication**: Unchanged content takes no additional space in a new snapshot.
- **Snapshot management**: View the space used by each snapshot in a snapshot chain and configure automatic cleanup.
- **Chunk previews and rollback**: Preview how a chunk looked in an earlier snapshot and restore a single chunk from a selected snapshot.

    Chunk rollback is available both in game and through the CLI while the server is offline. The offline option can help recover a problematic chunk, including one that crashes the game because it contains too many entities.
- **World export**: Export a snapshot as a Minecraft world.
- **Archive compression**: Create full or differential archives, then restore an archive as a Minecraft world or import it into a snapshot repository.

    Archives are intended for storing and distributing worlds. After downloading a full archive once, later differential archives transfer only the changed data, so you can sync a server world to a local machine, edit it there, and sync it back. This saves bandwidth.

### LRH and Linear

**LRH is a streaming implementation based on the Linear region format** and is used to compress and store Region data in FoldBackup. The original project is [xymb-endcrystalme/LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools). Thanks to its authors for the format design and implementation.

FoldBackup also introduces the LRHD format to record chunk differences in archives, enabling chunk-level differential backups and storage.

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

By default, backups are stored under `foldbackups/` in the server's working directory. You can change this with `general.backupPath`, which is the backup root: a dedicated server uses it directly as the repository, while singleplayer uses `foldbackups/<world-folder-name>` so each world keeps its own repository. Before a backup, FoldBackup forces the world to save and temporarily disables automatic saving. Saving is restored as soon as the world scan finishes, so committing the snapshot and cleaning up do not keep it disabled. If the scan makes no progress for `scheduling.savePauseStallSeconds` (default 300), the backup is aborted, saving is restored, players are warned, and the next scheduled attempt waits for a full interval.

Automatic backups are enabled by default. The interval counts accumulated time while players are online. Manual backups are also subject to the minimum interval between backups; use `/foldbackup backup --force` to bypass it.

### Restore an Entire World

You can restore an entire world through the TUI or CLI.

From the world's repository directory (`foldbackups/` on a dedicated server, `foldbackups/<world-folder-name>` in singleplayer), run the following commands to export the bundled runtime libraries (this avoids dependency conflicts between the mod and the CLI), then open the menu:

```bash
# Dedicated server: foldbackups/
java -jar ../mods/foldbackup-0.3.0.jar extract
java -jar ../mods/foldbackup-0.3.0.jar tui

# Singleplayer: foldbackups/<world-folder-name>
java -jar ../../mods/foldbackup-0.3.0.jar extract
java -jar ../../mods/foldbackup-0.3.0.jar tui
```

In the menu, select "2. Export world files" and follow the prompts to choose a snapshot, a directory or a ZIP, and the output path. Press Enter to accept the default path (`world-export/<snapshot-id>`, or the same name with `.zip` for a ZIP).

For a directory export, the target can be an existing game save: FoldBackup updates only the content that differs from the snapshot and never rewrites identical files. Stop the server before restoring. After the check, choose "1 = overwrite" to keep files in the target that the snapshot does not contain, or "2 = delete extra" to remove them so the target matches the snapshot exactly. The thread-count prompt can be accepted with Enter.

The CLI can do the same export: `snapshot export <snapshot-id> --output <directory>`. `--zip` writes a ZIP; for a directory export, `--delete-extra` removes content that is not in the snapshot; `--dry-run` prints the statistics without writing or deleting.

### Quick Start the TUI

If you prefer a launcher, save one of the scripts below into the repository directory as `foldbackup-tui.bat` or `foldbackup-tui.sh` (then `chmod +x` it on Linux). The script looks for the JAR beside itself, then in `../mods`, then in `../../mods`, so the default server and singleplayer layouts work without edits.

`foldbackup-tui.bat`:

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

`foldbackup-tui.sh`:

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

### Roll Back a Chunk

You can preview and roll back a single chunk in game, or use the CLI to roll back a chunk directly in an offline world.

In game, use `/foldbackup preview <x> <z> [snapshot-id]` to preview a chunk from a snapshot. This changes how the target chunk appears on the client to show its state at the time of that snapshot; it does not modify the actual chunk. `x` and `z` are chunk coordinates. You can find them on the F3 screen, or divide the block coordinates by 16 and round down. A blue particle effect marks the previewed chunk. Use `/foldbackup preview exit` to end the preview.

To roll back a chunk in game, use `/foldbackup rollback chunk <x> <z> [snapshot-id]`. You can specify a dimension with `/foldbackup rollback chunk in <dimension> <x> <z> [snapshot-id]`. The target chunk must already be loaded in memory. For an unloaded chunk, use the offline CLI method below. If you omit the snapshot ID, the latest snapshot is used.

For the CLI examples below, assume the current directory is `foldbackups` and the server world directory is its sibling, `world`. If you run the commands elsewhere, adjust the JAR, world, and snapshot paths accordingly. You can also specify the snapshot directory with `--snapshot-root <directory>`. In singleplayer the repository is `foldbackups/<world-folder-name>`, so point `--snapshot-root` at that directory. **Stop the server before performing an offline rollback.**

List the snapshots:

```bash
java -jar ../mods/foldbackup-0.3.0.jar snapshot list
```

Copy the target snapshot ID from the list, then roll back the chunk. The following command restores chunk `[x, z]` in the Overworld directory `world` to its state in that snapshot:

```bash
java -jar ../mods/foldbackup-0.3.0.jar snapshot rollback <snapshot-id> --world ../world --chunk <chunk-x> <chunk-z>
```

`--world` points to the target dimension directory: use the world root for the Overworld, `../world/DIM-1` for the Nether, `../world/DIM1` for the End, or the corresponding `dimensions/<namespace>/<path>` directory for a custom dimension. `--chunk` takes signed integer chunk coordinates, not block coordinates. For example, block coordinate `-1` belongs to chunk coordinate `-1`. The CLI asks for confirmation before proceeding; append `--yes` to skip the prompt.

The CLI restores the target chunk's terrain, block entities, recoverable entity data, and that chunk's points of interest exactly as recorded in the snapshot, including ticket occupancy. It does not restore player data or world-level configuration.

### Full and Differential Archives

Archives are intended for storing and distributing worlds, with high compression ratios as a priority. Because creating them uses substantial resources, archive operations are available only through the CLI and TUI.

Archives are created from snapshots, so you need a snapshot before creating an archive. You can create one in game or through the TUI or CLI. In the TUI, select "4. Create archive", choose a snapshot and the archive type. A differential archive also asks for an earlier snapshot to compare against, so it stores only what changed.

An archive can be restored as a Minecraft world ("6. Restore as world files" in the TUI) or imported as a snapshot into a snapshot repository. Restoring as a world also updates an existing save in place, and choosing "delete extra" removes files that are not in the archive. If you distribute a world using archives, you can package the mod and TUI launcher script with them (the script content is shown earlier in this README).

The archive format is independent of the Minecraft version. You can use the CLI or TUI to compress worlds from versions that the mod does not support.

## Common In-Game Commands

| Command | Purpose |
| --- | --- |
| `/foldbackup backup` | Create a snapshot manually |
| `/foldbackup backup --force` | Create a backup without waiting for the minimum interval |
| `/foldbackup list` | List snapshots |
| `/foldbackup status` | Show task status |
| `/foldbackup info <snapshot-id>` | Show snapshot details |
| `/foldbackup delete <snapshot-id>` | Delete a snapshot |
| `/foldbackup preview <x> <z> [snapshot-id]` | Preview a historical chunk in the current dimension |
| `/foldbackup preview exit` | End the preview |
| `/foldbackup rollback chunk <x> <z> [snapshot-id]` | Roll back a single chunk in the current dimension |
| `/foldbackup rollback chunk in <dimension> <x> <z> [snapshot-id]` | Roll back a single chunk in a specified dimension |

## Key Configuration Options

Configuration is managed through NeoForge's SERVER configuration system. Names in the table use the form `group.key`.

| Option | Default | Description |
| --- | --- | --- |
| `general.enabled` | `true` | Enable automatic backups |
| `general.backupPath` | `./foldbackups` | Backup root relative to the server's working directory; absolute paths are also supported. A dedicated server uses it as the repository; singleplayer creates one repository per world folder under it |
| `general.compressionLevel` | `6` | Zstd compression level, from 1 to 22 |
| `scheduling.intervalMinutes` | `120` | Accumulated player online time between automatic backups, in minutes |
| `scheduling.minIntervalMinutes` | `10` | Minimum wall-clock time between backups, in minutes |
| `scheduling.savePauseStallSeconds` | `300` | Abort the backup and restore world auto-saving when the world scan makes no progress for this long; 0 disables the check |
| `scheduling.backupOnStart` | `false` | Back up on startup |
| `scheduling.backupOnStop` | `false` | Back up on shutdown |
| `retention.enabled` | `true` | Enable automatic cleanup |
| `retention.maxSizeGB` | `50` | Target repository size after cleanup, in GiB; 0 means unlimited |
| `retention.minFreeSpaceGB` | `10` | Target free space on the file system, in GiB; 0 disables this limit |
| `retention.maxAgeDays` | `30` | Maximum snapshot retention age, in days; 0 means unlimited |
| `performance.regionThreads` | `0` | Number of worker threads; 0 selects automatically |
| `performance.regionMemoryLimitMB` | `0` | Memory budget for streaming tasks; 0 selects automatically |

**Automatic cleanup may delete all historical snapshots, including manually created backups.** Repository size and free-space limits may also cause snapshots younger than 30 days to be deleted. The free-space target does not mean space has been reserved for the next backup. Set all three cleanup limits to 0 to disable automatic deletion, or turn off automatic cleanup with `retention.enabled`.

`general.excludePatterns` matches paths relative to the world directory, using `/` as the path separator. By default, it excludes `**/session.lock`, `**/backups`, `**/.backups`, and `**/*.wip`. If a directory matches, its entire subtree is excluded.

## Build from Source

For environment setup, test commands, and report locations, see the [Build and Test Guide](doc/构建与测试指南.md) (Chinese).

`./gradlew jar` produces `foldbackup-<version>.jar`. The JAR contains no TUI launcher script templates or script injection code, so it never writes launcher scripts into the repository directory.

## License and Acknowledgments

FoldBackup's project metadata declares the LGPL v3 license.

LRH is based on the Linear region format. The original project, [LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools), is licensed under BSD-2-Clause. Thanks to its original author and contributors.
