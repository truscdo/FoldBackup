package io.github.truscdo.foldbackup.core.console;

import io.github.truscdo.foldbackup.core.BackupExporter;
import io.github.truscdo.foldbackup.core.FormatUtil;
import io.github.truscdo.foldbackup.core.store.SnapshotReclamationEstimator;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link BackupExporter.SnapshotInfo} 表格与仓库空间摘要的统一渲染（CLI 与 TUI 共用）。
 * <ul>
 * <li>{@link #printSummary}：单行仓库空间摘要（Stored / Unique / Shared / All / index / Logical / Pinned）。</li>
 * <li>{@link #printSelection}：{@code # / ID / Date / Size / Unique} 动态宽度精简表，
 * 用于选择场景（TUI export / pin / delete / archive create）。</li>
 * <li>{@link #printList}：{@code # / ID / Date / Regions / Files / Size / Unique / Reclaim} 动态宽度完整表，
 * 用于 list 场景（TUI list 与 CLI list）。</li>
 * </ul>
 * 无状态，只做渲染，不碰控制台输入。
 */
public final class SnapshotTablePrinter {

    private static final String COLUMN_GAP = "  ";

    private SnapshotTablePrinter() {
    }

    /** 渲染顶部仓库空间摘要行。 */
    public static void printSummary(SnapshotReclamationEstimator.RepositorySpaceSummary summary, PrintStream out) {
        String pinnedPart = summary.pinnedCount() > 0
                ? summary.pinnedCount() + " (Held: " + FormatUtil.formatSize(summary.pinnedHeldBytes()) + ")"
                : "0";
        out.printf(Locale.ROOT,
                "Stored: %s (Unique: %s + Shared: %s [All: %s], index: %s) | Logical: %s (saved %.1f%%) | Pinned: %s%n",
                FormatUtil.formatSize(summary.storedBytes()),
                FormatUtil.formatSize(summary.totalUniqueBytes()),
                FormatUtil.formatSize(summary.totalSharedBytes()),
                FormatUtil.formatSize(summary.globalSharedBytes()),
                FormatUtil.formatSize(summary.indexBytes()),
                FormatUtil.formatSize(summary.logicalBytes()),
                summary.savedPercent(),
                pinnedPart);
    }

    /** 渲染选择表（{@code # / ID / Date / Size / Unique}），按内容动态计算列宽。 */
    public static void printSelection(List<BackupExporter.SnapshotInfo> snapshots, PrintStream out) {
        String[] headers = {"#", "ID", "Date", "Size", "Unique"};
        boolean[] rightAlign = {false, false, false, true, true};
        List<String[]> rows = new ArrayList<>(snapshots.size());
        int idx = 1;
        for (BackupExporter.SnapshotInfo sn : snapshots) {
            rows.add(new String[] {
                    String.valueOf(idx),
                    formatId(sn),
                    sn.date,
                    FormatUtil.formatSize(sn.compressedBytes),
                    FormatUtil.formatSize(sn.uniqueBytes)
            });
            idx++;
        }
        printTable(headers, rightAlign, rows, out);
    }

    /** 渲染完整列表（{@code # / ID / Date / Regions / Files / Size / Unique / Reclaim}），按内容动态计算列宽。 */
    public static void printList(List<BackupExporter.SnapshotInfo> snapshots, PrintStream out) {
        String[] headers = {"#", "ID", "Date", "Regions", "Files", "Size", "Unique", "Reclaim"};
        boolean[] rightAlign = {false, false, false, true, true, true, true, true};
        List<String[]> rows = new ArrayList<>(snapshots.size());
        int idx = 1;
        for (BackupExporter.SnapshotInfo sn : snapshots) {
            String reclaim = sn.pinned ? "-" : FormatUtil.formatSize(sn.cumulativeSpaceBytes);
            rows.add(new String[] {
                    String.valueOf(idx),
                    formatId(sn),
                    sn.date,
                    String.valueOf(sn.regions),
                    String.valueOf(sn.files),
                    FormatUtil.formatSize(sn.compressedBytes),
                    FormatUtil.formatSize(sn.uniqueBytes),
                    reclaim
            });
            idx++;
        }
        printTable(headers, rightAlign, rows, out);
    }

    private static void printTable(String[] headers, boolean[] rightAlign, List<String[]> rows, PrintStream out) {
        int cols = headers.length;
        int[] widths = new int[cols];
        for (int c = 0; c < cols; c++) {
            widths[c] = headers[c].length();
        }
        for (String[] row : rows) {
            for (int c = 0; c < cols; c++) {
                if (row[c].length() > widths[c]) {
                    widths[c] = row[c].length();
                }
            }
        }
        int totalWidth = COLUMN_GAP.length() * (cols - 1);
        for (int w : widths) {
            totalWidth += w;
        }
        out.println(formatRow(headers, widths, rightAlign));
        out.println("-".repeat(totalWidth));
        for (String[] row : rows) {
            out.println(formatRow(row, widths, rightAlign));
        }
    }

    private static String formatRow(String[] cells, int[] widths, boolean[] rightAlign) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cells.length; c++) {
            if (c > 0) {
                sb.append(COLUMN_GAP);
            }
            String cell = cells[c];
            int pad = widths[c] - cell.length();
            if (rightAlign[c]) {
                sb.append(" ".repeat(pad)).append(cell);
            } else {
                sb.append(cell).append(" ".repeat(pad));
            }
        }
        return sb.toString();
    }

    private static String formatId(BackupExporter.SnapshotInfo sn) {
        return sn.pinned ? sn.id + " [PIN]" : sn.id;
    }
}
