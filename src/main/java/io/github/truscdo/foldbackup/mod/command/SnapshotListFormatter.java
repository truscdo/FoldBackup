package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.core.BackupExporter;
import io.github.truscdo.foldbackup.core.FormatUtil;
import io.github.truscdo.foldbackup.core.PageSlice;
import io.github.truscdo.foldbackup.core.store.SnapshotReclamationEstimator;
import io.github.truscdo.foldbackup.mod.i18n.FoldBackupTranslations;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * 格式化 {@code /foldbackup list [page]} 的聊天气泡/控制台分页输出。
 * <p>
 * 保持单行精简（4 列数据 + 2 行短摘要）以契合 Minecraft 原版默认 {@code 320 px} 聊天框，
 * 并为玩家端附加悬停详情、点击查详情以及可点击 ASCII 翻页栏。
 */
final class SnapshotListFormatter {

    private SnapshotListFormatter() {
    }

    static List<Component> formatPage(
            BackupExporter.SnapshotListView view,
            PageSlice<BackupExporter.SnapshotInfo> slice,
            String language,
            boolean interactive) {
        List<Component> lines = new ArrayList<>(slice.items().size() + 5);

        if (slice.isMultiPage()) {
            lines.add(FoldBackupTranslations.componentForLanguage(
                    language,
                    "foldbackup.command.list.title_paged",
                    slice.totalItems(),
                    slice.page(),
                    slice.totalPages()));
        } else {
            lines.add(FoldBackupTranslations.componentForLanguage(
                    language,
                    "foldbackup.command.list.title",
                    slice.totalItems()));
        }

        SnapshotReclamationEstimator.RepositorySpaceSummary summary = view.summary();
        if (summary.pinnedCount() > 0) {
            lines.add(FoldBackupTranslations.componentForLanguage(
                    language,
                    "foldbackup.command.list.summary_storage_pinned",
                    FormatUtil.formatSize(summary.storedBytes()),
                    summary.pinnedCount(),
                    FormatUtil.formatSize(summary.pinnedHeldBytes())));
        } else {
            lines.add(FoldBackupTranslations.componentForLanguage(
                    language,
                    "foldbackup.command.list.summary_storage",
                    FormatUtil.formatSize(summary.storedBytes())));
        }
        lines.add(FoldBackupTranslations.componentForLanguage(
                language,
                "foldbackup.command.list.summary_breakdown",
                FormatUtil.formatSize(summary.totalUniqueBytes()),
                FormatUtil.formatSize(summary.totalSharedBytes()),
                FormatUtil.formatSize(summary.globalSharedBytes())));

        lines.add(FoldBackupTranslations.componentForLanguage(language, "foldbackup.command.list.header"));

        for (BackupExporter.SnapshotInfo snapshot : slice.items()) {
            lines.add(formatRow(snapshot, language, interactive));
        }

        if (slice.isMultiPage()) {
            lines.add(interactive
                    ? buildInteractiveNav(slice, language)
                    : FoldBackupTranslations.componentForLanguage(
                            language,
                            "foldbackup.command.list.nav.console",
                            slice.page(),
                            slice.totalPages(),
                            slice.totalItems()));
        }
        return lines;
    }

    private static Component formatRow(BackupExporter.SnapshotInfo snapshot, String language, boolean interactive) {
        String rowSize = FormatUtil.formatSize(snapshot.compressedBytes);
        String rowUnique = FormatUtil.formatSize(snapshot.uniqueBytes);
        String text = snapshot.pinned
                ? FoldBackupTranslations.textForLanguage(
                        language, "foldbackup.command.list.row_pinned", snapshot.id, rowSize, rowUnique)
                : FoldBackupTranslations.textForLanguage(
                        language,
                        "foldbackup.command.list.row",
                        snapshot.id,
                        rowSize,
                        rowUnique,
                        FormatUtil.formatSize(snapshot.cumulativeSpaceBytes));
        MutableComponent line = Component.literal(text);
        if (interactive) {
            Component hoverText = FoldBackupTranslations.componentForLanguage(
                    language,
                    "foldbackup.command.list.row_hover",
                    snapshot.date,
                    snapshot.regions,
                    snapshot.files,
                    snapshot.id);
            line.withStyle(style -> style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/foldbackup info " + snapshot.id))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText)));
        }
        return line;
    }

    private static Component buildInteractiveNav(PageSlice<?> slice, String language) {
        String prevLabel = FoldBackupTranslations.textForLanguage(language, "foldbackup.command.list.nav.prev");
        String nextLabel = FoldBackupTranslations.textForLanguage(language, "foldbackup.command.list.nav.next");

        Component firstButton = buildNavButton(
                "[<<]",
                slice.hasPrevious(),
                ChatFormatting.GOLD,
                1,
                FoldBackupTranslations.componentForLanguage(language, "foldbackup.command.list.nav.first_hover"));
        Component prevButton = buildNavButton(
                "[< " + prevLabel + "]",
                slice.hasPrevious(),
                ChatFormatting.YELLOW,
                slice.page() - 1,
                FoldBackupTranslations.componentForLanguage(
                        language, "foldbackup.command.list.nav.page_hover", slice.page() - 1));
        Component nextButton = buildNavButton(
                "[" + nextLabel + " >]",
                slice.hasNext(),
                ChatFormatting.YELLOW,
                slice.page() + 1,
                FoldBackupTranslations.componentForLanguage(
                        language, "foldbackup.command.list.nav.page_hover", slice.page() + 1));
        Component lastButton = buildNavButton(
                "[>>]",
                slice.hasNext(),
                ChatFormatting.GOLD,
                slice.totalPages(),
                FoldBackupTranslations.componentForLanguage(
                        language, "foldbackup.command.list.nav.last_hover", slice.totalPages()));

        return Component.empty()
                .append(firstButton)
                .append(Component.literal(" "))
                .append(prevButton)
                .append(Component.literal("  "))
                .append(Component.literal(String.valueOf(slice.page())).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" / ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(slice.totalPages())).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  "))
                .append(nextButton)
                .append(Component.literal(" "))
                .append(lastButton);
    }

    private static Component buildNavButton(
            String label, boolean enabled, ChatFormatting enabledColor, int targetPage, Component hoverText) {
        MutableComponent button = Component.literal(label);
        if (!enabled) {
            return button.withStyle(ChatFormatting.DARK_GRAY);
        }
        return button.withStyle(style -> style
                .withColor(enabledColor)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/foldbackup list " + targetPage))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText)));
    }
}
