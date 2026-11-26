# FoldBackup

[English](../../README.md) | 简体中文 | [Español](README.es.md) | [Português (Brasil)](README.pt-br.md) | [Русский](README.ru.md)

FoldBackup 是面向 Minecraft NeoForge 服务端的世界备份工具，支持自动与手动`快照备份`、`归档压缩`、`历史区块预览`和`区块回档`。

快照的储存使用了与 Git 类似的内容去重方案，每次备份仅记录更改和新增的文件，同时使用 LRH 格式储存 Region 文件。在 1.21.1 NeoForge 80+ Mod 生存服中，压缩等级为 19 时的`归档`文件可以做到仅为原存档的三分之一大小。

同一个 JAR 既可以安装为 NeoForge 模组，也可以作为独立工具运行，提供独立命令行（CLI）和交互菜单（TUI），方便在服务器关闭时使用功能。

- **运行环境**：Minecraft `1.21.1`、NeoForge `21.1+`、Java `21+`（独立 CLI / TUI 模式不受游戏版本限制）
- **安装要求**：仅需安装在**服务端**（或单机客户端）；连入服务器的玩家客户端**无需**安装本模组即可使用全部功能（包括游戏内区块历史预览）

## 主要功能

- **在线备份**：按玩家在线时间定时备份，支持手动触发，以及可选的启动、关闭时备份
- **内容去重**：新建快照的未变化内容不占用空间
- **快照管理**：查看仓库空间摘要及每个快照的独占空间、共享空间与累计可清空间，支持保留（Pin）重要快照免受自动清理保护、在批量删除前预估可释放空间（`--dry-run`），以及可配置的自动清理
- **区块预览与回档**：在游戏中预览区块历史景象，允许指定快照中的单个区块恢复到世界中

    同时支持游戏内区块回档与游戏外 CLI 直接对存档进行区块回档，可以用于处理异常区块或者实体过多导致游戏崩溃的情况
- **存档导出**：将快照导出为 Minecraft 游戏存档
- **归档压缩**：可以创建完整归档或差分归档，并使用归档文件恢复为游戏存档或导入到快照仓库

    归档用于储存和分发存档。下载一次完整归档后，后续差分归档只需传输更改部分，即可把服务器存档同步到本地，编辑后再同步回去，节约带宽

> [!NOTE]
> **快照（Snapshot）与归档（Archive）的区别**：
>
> - **快照**：日常高频自动/手动备份，增量去重存储在本地仓库中，用于快速回档、导出和单区块恢复。
> - **归档**：面向高压缩率的独立压缩文件（支持完整归档与差分归档），适合异地冷备、跨机器同步或分发存档。

### LRH 与 Linear

**LRH 是基于 Linear region 格式的流式实现版本**，用于 FoldBackup 的 Region 数据压缩与存储。原始项目为 [xymb-endcrystalme/LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools)，感谢其提供的格式设计与实现。

另外新增了 LRHD 格式用于记录归档中的区块差异，实现区块级差异备份与储存。

## 快速开始

### 创建备份

1. 将 FoldBackup JAR 放入 NeoForge 服务端的 `mods/` 目录。
2. 启动服务器，等待世界加载和 FoldBackup 初始化完成。
3. 在游戏内以权限等级 2 或更高的身份执行：

   ```text
   /foldbackup backup --force
   /foldbackup list
   ```

   单机存档即使未开启作弊，主机玩家也能使用这些命令；通过局域网加入的其它玩家不能使用。

默认备份目录是服务端运行目录下的 `foldbackups/`，可以通过 `general.backupPath` 修改。该配置是备份根：专用服务器直接以它为仓库，单机则使用 `foldbackups/<存档目录名>`，每个存档各自持有独立仓库。备份前会强制保存世界并暂时关闭自动保存；世界扫描结束即恢复保存，之后的快照提交与清理不再占用暂停时间。如果扫描连续 `scheduling.savePauseStallSeconds`（默认 300）秒没有进展，本次备份会被中止、保存立即恢复、在线玩家收到提示，下一次计划备份需要重新攒满一个完整间隔。

默认开启自动备份，间隔按有玩家在线时的累计时间计算。手动备份也受最小备份间隔限制，需要跳过该间隔时可以使用 `/foldbackup backup --force`。

### 整体回档

回档整个游戏存档可以使用 TUI 或者 CLI。

在该世界的仓库目录（专用服务器为 `foldbackups/`，单机为 `foldbackups/<存档目录名>`）执行以下命令，先导出随 JAR 打包的运行库（这个设计是为了避免 Mod 与 CLI 的依赖冲突），再启动菜单。请将 `<版本>` 替换为实际 JAR 文件名：

```bash
# 服务器 foldbackups/
java -jar ../mods/foldbackup-<版本>.jar extract
java -jar ../mods/foldbackup-<版本>.jar tui

# 单机 foldbackups/<存档目录名>
java -jar ../../mods/foldbackup-<版本>.jar extract
java -jar ../../mods/foldbackup-<版本>.jar tui
```

在菜单中选择“2. Export world files”（导出世界文件），按提示选择快照、导出为目录还是 ZIP，再输入输出路径。路径可以直接回车使用默认值（`world-export/<快照ID>`，ZIP 为同名 `.zip`）。

> [!IMPORTANT]
> 导出到正在使用的游戏存档目录进行回档前，**请先停止服务器**。

导出为目录时，目标可以指向已有的游戏存档：程序只更新与快照不同的文件，相同的文件会进行跳过。导出前会进行检查，检查完成后选择“1 = overwrite”会保留存档中多出的文件，选择“2 = delete extra”会删除这些文件，使存档与快照完全一致。之后的线程数提示可以直接回车使用默认设置。

CLI 也可以完成同样的导出：`snapshot export <快照ID> --output <目录>`。`--zip` 导出 ZIP；目录导出可加 `--delete-extra` 删除多出的内容；`--dry-run` 只输出统计，不写入也不删除。

### 快速启动 TUI

如果希望少敲命令，可以把下面的脚本另存到仓库目录，命名为 `foldbackup-tui.bat` 或 `foldbackup-tui.sh`（Linux 下需 `chmod +x`）。脚本会依次在自身所在目录、`../mods`、`../../mods` 中查找 JAR。

<details>
<summary>点击展开 Windows 启动脚本（<code>foldbackup-tui.bat</code>）</summary>

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

</details>

<details>
<summary>点击展开 Linux / macOS 启动脚本（<code>foldbackup-tui.sh</code>）</summary>

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

</details>

### 区块回档

支持游戏内使用命令预览与回档单个区块，同时也支持使用 CLI 直接操作游戏存档回档单个区块。

在游戏中可以使用 `/foldbackup preview from <快照ID>`（默认预览脚下所在区块）或 `/foldbackup preview from <快照ID> at <x> <z>` 对目标快照和目标区块进行预览，这条命令会改变目标区块在客户端显示的内容，替换为目标快照时间的区块景象，不会真正地修改区块；
`x` 与 `z` 是区块坐标，可以通过 F3 信息显示，或者将方块坐标除以 16 并向下取整获得；
预览时目标区块会显示蓝色粒子效果进行区分，使用 `/foldbackup preview exit` 即可退出预览。

游戏内回档可使用 `/foldbackup rollback chunk from <快照ID>`（默认回档玩家当前所在区块）；也可以追加 `at <x> <z>` 或 `in <维度> at <x> <z>` 指定坐标和维度（服务端控制台或 RCON 执行时必须显式指定坐标），或使用 `blocks`（仅恢复地形、方块实体与 POI，保留当前实体）与 `entities`（仅恢复实体，不动地形与方块实体）限定范围，例如 `/foldbackup rollback chunk blocks from <快照ID> at <x> <z>` 或 `/foldbackup rollback chunk entities from <快照ID> in <维度> at <x> <z>`。目标区块必须已经加载到内存中；未加载的区块请使用下面的 CLI 离线回档方式。

当需要使用 CLI 回档时，以下示例假设当前目录为 `foldbackups`，且服务端存档目录为同级的 `world`。如果在其它目录执行，需要相应调整 JAR、存档和快照目录路径；也可以用 `--snapshot-root <目录>` 指定快照目录。单机存档的仓库位于 `foldbackups/<存档目录名>`，需要把 `--snapshot-root` 指向该目录。**离线回档前必须先关闭服务端。**

列出快照：

```bash
java -jar ../mods/foldbackup-<版本>.jar snapshot list
```

从列表中复制目标快照 ID，然后执行区块回档。以下命令会将主世界 `world` 中区块 `[x, z]` 恢复到该快照的状态：

```bash
java -jar ../mods/foldbackup-<版本>.jar snapshot rollback <快照ID> --world ../world --chunk <区块X> <区块Z> [--scope <all|blocks|entities>]
```

`--world` 指向目标维度目录：主世界使用存档根目录，下界使用 `../world/DIM-1`，末地使用 `../world/DIM1`，自定义维度使用其 `dimensions/<命名空间>/<路径>` 目录。`--chunk` 接收有符号整数区块坐标，不是方块坐标。`--scope` 默认为 `all`，也可指定为 `blocks`（仅地形、方块实体与 POI）或 `entities`（仅实体）。CLI 会在执行前要求确认；可追加 `--yes` 跳过确认。

默认情况下，CLI 会恢复目标区块的地形、方块实体、可恢复的实体数据，以及快照中该区块的兴趣点（POI）数据，包括票据占用状态；不会恢复玩家数据或世界级配置。

### 完整归档与差分归档

归档用于储存和分发存档，以高压缩率为目标，性能占用较大，因此仅提供CLI和TUI实现。

创建归档前需要有对应的快照，可以在游戏内备份，也可以用TUI或CLI创建。在TUI主菜单选择“5. Create archive”（创建归档），选择快照和归档类型即可；差分归档需要选择一个更早的快照作为对比基准，只保存变化的部分。

归档可以恢复为游戏存档（TUI 主菜单为“7. Restore as world files”）或者导入为快照仓库中的快照。恢复为存档时如果目标位置已有游戏存档，同样会以游戏存档为基础进行更新，选择“delete extra”会删除归档中不存在的文件。将归档作为存档分发方案时，可以将mod与tui启动脚本一起打包（脚本内容见上方说明）。

归档格式与游戏版本无关，对于mod不支持的版本，也可以使用CLI或者TUI进行压缩。

## 常用游戏内命令

| 命令 | 用途 |
| --- | --- |
| `/foldbackup backup` | 手动创建快照 |
| `/foldbackup backup --force` | 跳过最小备份间隔触发备份 |
| `/foldbackup list [页码]` | 分页查看快照列表（每页 10 条）与仓库空间摘要（独占、共享与累计可清空间） |
| `/foldbackup status` | 查看任务状态 |
| `/foldbackup info <快照ID>` | 查看快照详情与当前独占/共享空间分布 |
| `/foldbackup delete [--dry-run] <id1> [id2 ...]` | 删除一个或多个快照，或加 `--dry-run` 预估可释放空间 |
| `/foldbackup pin <快照ID>` | 保留快照，防止被自动清理或手动删除 |
| `/foldbackup unpin <快照ID>` | 取消快照的保留保护 |
| `/foldbackup preview from <快照ID> [at <x> <z>]` | 预览当前维度的历史区块（省略坐标时默认当前区块） |
| `/foldbackup preview exit` | 结束预览 |
| `/foldbackup rollback chunk from <快照ID> [at <x> <z>]` | 回档当前维度的单个区块（省略坐标时默认当前区块） |
| `/foldbackup rollback chunk from <快照ID> in <维度> at <x> <z>` | 回档指定维度的单个区块 |
| `/foldbackup rollback chunk blocks from <快照ID> [in <维度> at <x> <z> \| at <x> <z>]` | 仅回档区块的方块、方块实体与 POI（保留当前实体） |
| `/foldbackup rollback chunk entities from <快照ID> [in <维度> at <x> <z> \| at <x> <z>]` | 仅回档区块的实体（不改动方块与方块实体） |

<details>
<summary>点击展开快照列表与空间统计说明及示例（Mod / TUI / CLI）</summary>

快照列表按**最新优先**展示。Mod 在中文环境下显示中文表头，TUI（`1. List snapshots`）与 CLI（`snapshot list`）共用同一套英文输出，各项数据一一对应。

| Mod（中文） | TUI / CLI（英文） | 含义 |
| --- | --- | --- |
| `总占用` | `Stored` | 仓库实际磁盘占用（去重后的数据块 + `index.db` 索引文件） |
| `独占` / `共享 (全局)` | `Unique` / `Shared [All]` | 仅被单个快照引用 / 被多个快照共用的空间；全局(All)为被全部快照共同引用的基础数据 |
| `保留 (独占)` | `Pinned (Unique)` | 已保留快照数量，以及仅由保留快照集合独占的空间 |
| — | `Logical (saved %)` | 未跨快照去重时的压缩体积总和，以及去重节省比例 |
| `大小` | `Size` | 该快照自身的压缩体积（不等于删除它可释放的空间） |
| `独占`（行内） | `Unique`（列） | 单独删除该快照可立即释放的空间 |
| `累计可清` / `[PIN]` | `Reclaim` / `-` | 按自动清理顺序，从最旧的未保留快照起连续删到当前行（跳过 `[PIN]`）总共可释放的空间 |

**TUI `1. List snapshots`（与 CLI `snapshot list` 相同）示例：**

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

- **交互与分页**：Mod 每页 10 条，玩家在聊天栏中悬停快照行可查看时间、区域数与额外文件数，点击可直接运行 `/foldbackup info <快照ID>`，多页时底部显示可点击翻页栏；TUI 每页 15 条，超过 1 页时底部提示翻页按键（导出、删除、保留、创建归档等选择界面使用 `# / ID / Date / Size / Unique` 精简表）。
- **批量删除预估**：需要删除任意组合的快照时，可用 `/foldbackup delete --dry-run`、CLI `snapshot delete --dry-run` 或 TUI 删除确认界面查看精确释放量。

</details>

## 关键配置

配置文件默认位于运行目录下的 `config/foldbackup-server.toml`。下表中的名称使用“分组.键名”表示。

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `general.enabled` | `true` | 开启自动备份 |
| `general.backupPath` | `./foldbackups` | 相对于服务端运行目录的备份根，也支持绝对路径。专用服务器直接以它为仓库；单机在其下按存档目录名各建一个仓库 |
| `general.compressionLevel` | `6` | Zstd 压缩级别，范围 1–22 |
| `scheduling.intervalMinutes` | `30` | 自动备份的玩家在线时间累计间隔，单位分钟 |
| `scheduling.minIntervalMinutes` | `10` | 两次备份的最小墙钟间隔，单位分钟 |
| `scheduling.savePauseStallSeconds` | `300` | 世界扫描无进展多少秒后中止备份并恢复自动保存；0 表示不检查 |
| `scheduling.backupOnStart` | `false` | 启动时备份 |
| `scheduling.backupOnStop` | `false` | 关闭时备份 |
| `retention.enabled` | `true` | 开启自动清理 |
| `retention.maxSizeGB` | `50` | 清理后的仓库容量目标，单位 GiB；0 表示不限 |
| `retention.minFreeSpaceGB` | `5` | 文件系统剩余空间目标，单位 GiB；0 表示禁用 |
| `retention.maxAgeDays` | `0` | 快照最大保留天数；0 表示不限 |
| `performance.regionThreads` | `0` | 处理工作线程数，0 表示自动 |
| `performance.regionMemoryLimitMB` | `0` | 流式任务内存预算，0 表示自动 |
| `logging.progressLogIntervalSeconds` | `3` | 进度日志的输出间隔秒数（1 最详细，300 最简略） |
| `localization.defaultLanguage` | `en_us` | 未开启玩家语言检测或上报语言不受支持时使用的语言；可选 `zh_cn`、`en_us`、`es_es`、`pt_br`、`ru_ru` |
| `localization.autoDetectPlayerLanguage` | `true` | 玩家上报的语言受支持时按其显示 |

> [!WARNING]
> **自动清理可能删除全部未保留的快照。** 默认只限制仓库容量（50 GiB）与剩余空间（5 GiB），因此自动清理可能为满足这两个目标而删除较新的快照。
>
> 重要快照可使用 `/foldbackup pin <快照ID>`（或通过 CLI / TUI）加以保护；三个清理限制均设为 0 时禁用自动删除，也可以通过 `retention.enabled` 关闭自动清理。

`general.excludePatterns` 按世界目录内的相对路径匹配，路径分隔符使用 `/`。默认排除 `**/session.lock`、`**/backups`、`**/.backups`、`**/*.wip` 和 `**/DistantHorizons.sqlite`；匹配目录时会排除整个子目录树。

## 许可证与致谢

FoldBackup 的项目元数据声明采用 LGPL v3 许可证。

LRH 基于 Linear region 格式实现，原始项目 [LinearRegionFileFormatTools](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools) 采用 BSD-2-Clause 许可证。感谢原作者及贡献者的工作。
