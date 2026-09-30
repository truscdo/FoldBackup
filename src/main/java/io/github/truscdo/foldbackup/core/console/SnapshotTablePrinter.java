package io.github.truscdo.foldbackup.core.console;

import io.github.truscdo.foldbackup.core.BackupExporter;
import io.github.truscdo.foldbackup.core.FormatUtil;

import java.io.PrintStream;
import java.util.List;

/**
 * {@link BackupExporter.SnapshotInfo} 表格的统一渲染（CLI 与 TUI 共用）。
 * <ul>
 * <li>{@link #printSelection}：{@code # / ID / Date / Size} 精简表，
 * 用于选择场景（TUI export/delete/archive create）。</li>
 * <li>{@link #printList}：含 {@code Regions / Files} 列的完整表，
 * 用于 list 场景（TUI list 与 CLI list）。</li>
 * </ul>
 * 无状态，只做渲染，不碰控制台输入。
 */
public final class SnapshotTablePrinter {

    private SnapshotTablePrinter() {
    }

    /** 渲染选择表（{@code # / ID / Date / Size}）。 */
    public static void printSelection(List<BackupExporter.SnapshotInfo> snapshots, PrintStream out) {
        out.printf("%-4s %-36s %-22s %12s%n", "#", "ID", "Date", "Size");
        out.println("-".repeat(80));
        int idx = 1;
        for (BackupExporter.SnapshotInfo sn : snapshots) {
            out.printf("%-4d %-36s %-22s %11s%n",
                    idx, sn.id, sn.date, FormatUtil.formatSize(sn.compressedBytes));
            idx++;
        }
    }

    /** 渲染完整列表（{@code # / ID / Date / Regions / Files / Size / Cumulative Space}）。 */
    public static void printList(List<BackupExporter.SnapshotInfo> snapshots, PrintStream out) {
        out.printf("%-4s %-36s %-22s %8s %6s %12s %16s%n",
                "#", "ID", "Date", "Regions", "Files", "Size", "Cumulative Space");
        out.println("-".repeat(115));
        int idx = 1;
        for (BackupExporter.SnapshotInfo sn : snapshots) {
            out.printf("%-4d %-36s %-22s %8d %6d %11s %15s%n",
                    idx, sn.id, sn.date, sn.regions, sn.files,
                    FormatUtil.formatSize(sn.compressedBytes),
                    FormatUtil.formatSize(sn.cumulativeSpaceBytes));
            idx++;
        }
    }
}
