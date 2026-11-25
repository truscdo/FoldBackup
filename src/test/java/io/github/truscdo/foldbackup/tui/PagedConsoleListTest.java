package io.github.truscdo.foldbackup.tui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link PagedConsoleList} 单元测试：翻页指令解析、浏览分页、跨页单选与跨页区间多选。 */
class PagedConsoleListTest {

    @Test
    void parseNavCommandHandlesValidInvalidAndShowAll() {
        assertEquals(PagedConsoleList.NavAction.NEXT, PagedConsoleList.parseNavCommand(" n ", false).action());
        assertEquals(PagedConsoleList.NavAction.PREV, PagedConsoleList.parseNavCommand("P", false).action());
        assertEquals(PagedConsoleList.NavAction.SHOW_ALL, PagedConsoleList.parseNavCommand("A", true).action());
        assertEquals(PagedConsoleList.NavAction.NONE, PagedConsoleList.parseNavCommand("a", false).action());

        var g2 = PagedConsoleList.parseNavCommand("g 2", false);
        assertEquals(PagedConsoleList.NavAction.GOTO, g2.action());
        assertEquals(2, g2.targetPage());

        var g10 = PagedConsoleList.parseNavCommand("G10", false);
        assertEquals(PagedConsoleList.NavAction.GOTO, g10.action());
        assertEquals(10, g10.targetPage());

        for (String bad : new String[] {"g", "g 0", "g -1", "g-2", "g abc"}) {
            assertEquals(PagedConsoleList.NavAction.INVALID_GOTO,
                    PagedConsoleList.parseNavCommand(bad, false).action(), bad);
        }
        for (String nonNav : new String[] {"", "5", "2,4-5", "unknown"}) {
            assertEquals(PagedConsoleList.NavAction.NONE,
                    PagedConsoleList.parseNavCommand(nonNav, false).action(), nonNav);
        }
    }

    @Test
    void browseSinglePagePrintsDirectlyAndMultiPageSupportsNavAndShowAll() {
        List<String> items = List.of("item-1", "item-2", "item-3", "item-4", "item-5");

        // Single page prints directly without prompt input
        var singleOut = new ByteArrayOutputStream();
        PrintStream singleStream = new PrintStream(singleOut, true);
        PagedConsoleList.browse(
                new ConsolePrompt(new StringReader(""), singleStream),
                singleStream,
                items.subList(0, 2),
                5,
                slice -> singleStream.println("HDR " + slice.totalItems() + " multi=" + slice.isMultiPage()),
                (slice, out) -> slice.items().forEach(out::println),
                out -> out.println("ALL"));
        String singleText = singleOut.toString();
        assertTrue(singleText.contains("HDR 2 multi=false"), singleText);
        assertTrue(singleText.contains("ALL"), singleText);

        // Multi-page: p (boundary) -> n -> n -> n (boundary) -> g 9 (boundary) -> g abc -> g 1 -> a (show all)
        var multiOut = new ByteArrayOutputStream();
        PrintStream multiStream = new PrintStream(multiOut, true);
        String input = "p\nn\nn\nn\ng 9\ng abc\ng 1\na\n";
        PagedConsoleList.browse(
                new ConsolePrompt(new StringReader(input), multiStream),
                multiStream,
                items,
                2,
                slice -> multiStream.println("PAGE " + slice.page() + "/" + slice.totalPages()),
                (slice, out) -> out.println("SLICE " + slice.startIndex() + "-" + slice.endIndex() + " " + slice.items()),
                out -> out.println("ALL " + items));
        String multiText = multiOut.toString();
        assertTrue(multiText.contains("Already on the first page."), multiText);
        assertTrue(multiText.contains("SLICE 3-4 [item-3, item-4]"), multiText);
        assertTrue(multiText.contains("SLICE 5-5 [item-5]"), multiText);
        assertTrue(multiText.contains("Already on the last page (3)."), multiText);
        assertTrue(multiText.contains("Page must be between 1 and 3."), multiText);
        assertTrue(multiText.contains("Please enter a valid page number between 1 and 3."), multiText);
        assertTrue(multiText.contains("ALL [item-1, item-2, item-3, item-4, item-5]"), multiText);

        // Multi-page EOF exits cleanly
        var eofOut = new ByteArrayOutputStream();
        PrintStream eofStream = new PrintStream(eofOut, true);
        PagedConsoleList.browse(
                new ConsolePrompt(new StringReader(""), eofStream),
                eofStream,
                items,
                2,
                slice -> {},
                (slice, out) -> {},
                out -> {});
    }

    @Test
    void selectSingleSupportsCrossPageGlobalSelectionAndBoundaryHandling() {
        List<String> items = List.of("a", "b", "c", "d", "e");
        var outBuf = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(outBuf, true);

        // Start page 1 -> n -> invalid -> cross-page pick global #5 from page 2
        var prompt = new ConsolePrompt(new StringReader("n\nbad\n99\n5\n"), outStream);
        var res = PagedConsoleList.selectSingle(
                prompt,
                outStream,
                items,
                2,
                1,
                "Pick (0 = back): ",
                "Pick",
                (slice, out) -> out.println("ITEMS " + slice.startIndex() + "-" + slice.endIndex()));

        assertFalse(res.eof());
        assertFalse(res.back());
        assertEquals(5, res.index());
        assertEquals(2, res.currentPage());
        String text = outBuf.toString();
        assertTrue(text.contains("Page 1/3 (showing #1-#2 of 5):"), text);
        assertTrue(text.contains("Page 2/3 (showing #3-#4 of 5):"), text);
        assertTrue(text.contains("Please enter a number between 1 and 5"), text);
    }

    @Test
    void selectMultipleSupportsPageNavigationAndCrossPageRanges() {
        List<String> items = List.of("a", "b", "c", "d", "e");
        var outBuf = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(outBuf, true);

        var prompt = new ConsolePrompt(new StringReader("n\n5-4\n1,4-5\n"), outStream);
        var res = PagedConsoleList.selectMultiple(
                prompt,
                outStream,
                items,
                2,
                1,
                "Select snapshot(s) to delete (e.g. 5, 4-5, 2,4-5; 0 = back): ",
                (slice, out) -> out.println("ITEMS " + slice.startIndex() + "-" + slice.endIndex()));

        assertFalse(res.selection().eof());
        assertFalse(res.selection().back());
        assertEquals(2, res.currentPage());
        assertEquals(List.of(1, 4, 5), res.selection().indices());
        String text = outBuf.toString();
        assertTrue(text.contains("Select snapshot(s) to delete (1-5, e.g. 5, 4-5, 2,4-5;"), text);
        assertTrue(text.contains("Invalid selection."), text);
    }
}
