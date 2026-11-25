package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.core.BackupExporter;
import io.github.truscdo.foldbackup.core.PageSlice;
import io.github.truscdo.foldbackup.core.store.SnapshotReclamationEstimator;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link SnapshotListFormatter} 单元测试：验证单行排版、玩家端点击/悬停交互与控制台回退。 */
class SnapshotListFormatterTest {

    @Test
    void interactiveMultiPageAttachesRowClickHoverAndPageButtonsPerBoundary() {
        List<BackupExporter.SnapshotInfo> snapshots = sampleSnapshots(25);
        var summary = new SnapshotReclamationEstimator.RepositorySpaceSummary(
                100L * 1024 * 1024, 128L * 1024, 25L * 1024 * 1024, 75L * 1024 * 1024,
                50L * 1024 * 1024, 25L * 1024 * 1024, 250L * 1024 * 1024, 1, 5L * 1024 * 1024);
        var view = new BackupExporter.SnapshotListView(snapshots, summary);

        // Page 1: [<<] and [< Prev] disabled; [Next >] and [>>] clickable
        PageSlice<BackupExporter.SnapshotInfo> p1 = PageSlice.of(snapshots, 1, 10);
        List<Component> linesP1 = SnapshotListFormatter.formatPage(view, p1, "en_us", true);
        assertEquals(15, linesP1.size()); // title(1) + summary(2) + header(1) + rows(10) + nav(1)

        Component firstRow = linesP1.get(4);
        ClickEvent rowClick = firstRow.getStyle().getClickEvent();
        assertNotNull(rowClick);
        assertEquals(ClickEvent.Action.RUN_COMMAND, rowClick.getAction());
        assertEquals("/foldbackup info S-20260901_000025", rowClick.getValue());

        HoverEvent rowHover = firstRow.getStyle().getHoverEvent();
        assertNotNull(rowHover);
        assertEquals(HoverEvent.Action.SHOW_TEXT, rowHover.getAction());
        Component hoverContent = rowHover.getValue(HoverEvent.Action.SHOW_TEXT);
        assertNotNull(hoverContent);
        String hoverStr = hoverContent.getString();
        assertTrue(hoverStr.contains("2026-09-01 00:00:25"), hoverStr);
        assertTrue(hoverStr.contains("12"), hoverStr);
        assertTrue(hoverStr.contains("5"), hoverStr);
        assertTrue(hoverStr.contains("/foldbackup info S-20260901_000025"), hoverStr);

        Component navP1 = linesP1.getLast();
        List<Component> buttonsP1 = extractButtons(navP1);
        assertEquals(4, buttonsP1.size());
        assertDisabledButton(buttonsP1.get(0), "[<<]");
        assertDisabledButton(buttonsP1.get(1), "[< Prev]");
        assertEnabledButton(buttonsP1.get(2), "[Next >]", "/foldbackup list 2");
        assertEnabledButton(buttonsP1.get(3), "[>>]", "/foldbackup list 3");

        // Page 2: all 4 buttons enabled
        PageSlice<BackupExporter.SnapshotInfo> p2 = PageSlice.of(snapshots, 2, 10);
        List<Component> linesP2 = SnapshotListFormatter.formatPage(view, p2, "en_us", true);
        List<Component> buttonsP2 = extractButtons(linesP2.getLast());
        assertEnabledButton(buttonsP2.get(0), "[<<]", "/foldbackup list 1");
        assertEnabledButton(buttonsP2.get(1), "[< Prev]", "/foldbackup list 1");
        assertEnabledButton(buttonsP2.get(2), "[Next >]", "/foldbackup list 3");
        assertEnabledButton(buttonsP2.get(3), "[>>]", "/foldbackup list 3");

        // Page 3: [<<] and [< Prev] enabled; [Next >] and [>>] disabled
        PageSlice<BackupExporter.SnapshotInfo> p3 = PageSlice.of(snapshots, 3, 10);
        List<Component> linesP3 = SnapshotListFormatter.formatPage(view, p3, "zh_cn", true);
        assertEquals(10, linesP3.size()); // title(1) + summary(2) + header(1) + rows(5) + nav(1)
        List<Component> buttonsP3 = extractButtons(linesP3.getLast());
        assertEnabledButton(buttonsP3.get(0), "[<<]", "/foldbackup list 1");
        assertEnabledButton(buttonsP3.get(1), "[< 上一页]", "/foldbackup list 2");
        assertDisabledButton(buttonsP3.get(2), "[下一页 >]");
        assertDisabledButton(buttonsP3.get(3), "[>>]");
    }

    @Test
    void nonInteractiveModeOmitsRowEventsAndPrintsConsoleNavigationHint() {
        List<BackupExporter.SnapshotInfo> snapshots = sampleSnapshots(15);
        var summary = new SnapshotReclamationEstimator.RepositorySpaceSummary(
                50L * 1024 * 1024, 0L, 15L * 1024 * 1024, 35L * 1024 * 1024,
                20L * 1024 * 1024, 15L * 1024 * 1024, 100L * 1024 * 1024, 0, 0L);
        var view = new BackupExporter.SnapshotListView(snapshots, summary);
        PageSlice<BackupExporter.SnapshotInfo> slice = PageSlice.of(snapshots, 1, 10);

        List<Component> lines = SnapshotListFormatter.formatPage(view, slice, "en_us", false);
        assertEquals(15, lines.size());
        Component row = lines.get(4);
        assertNull(row.getStyle().getClickEvent());
        assertNull(row.getStyle().getHoverEvent());

        Component footer = lines.getLast();
        assertNull(footer.getStyle().getClickEvent());
        assertTrue(footer.getString().contains("/foldbackup list <page>"), footer.getString());
    }

    private static List<BackupExporter.SnapshotInfo> sampleSnapshots(int count) {
        List<BackupExporter.SnapshotInfo> list = new ArrayList<>(count);
        for (int i = count; i >= 1; i--) {
            String suffix = String.format("%06d", i);
            list.add(new BackupExporter.SnapshotInfo(
                    i * 1000L,
                    "S-20260901_" + suffix,
                    "2026-09-01 00:00:" + String.format("%02d", i % 60),
                    12,
                    5,
                    10L * 1024 * 1024,
                    1024L * 1024,
                    i * 1024L * 1024,
                    i == count - 1));
        }
        return list;
    }

    private static List<Component> extractButtons(Component nav) {
        return nav.getSiblings().stream()
                .filter(c -> c.getString().startsWith("["))
                .toList();
    }

    private static void assertDisabledButton(Component button, String expectedText) {
        assertEquals(expectedText, button.getString());
        assertNull(button.getStyle().getClickEvent());
        assertNull(button.getStyle().getHoverEvent());
        assertEquals(ChatFormatting.DARK_GRAY.getColor(), button.getStyle().getColor().getValue());
    }

    private static void assertEnabledButton(Component button, String expectedText, String expectedCommand) {
        assertEquals(expectedText, button.getString());
        assertNotNull(button.getStyle().getClickEvent());
        assertEquals(ClickEvent.Action.RUN_COMMAND, button.getStyle().getClickEvent().getAction());
        assertEquals(expectedCommand, button.getStyle().getClickEvent().getValue());
        assertNotNull(button.getStyle().getHoverEvent());
    }
}
