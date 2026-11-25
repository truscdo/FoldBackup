package io.github.truscdo.foldbackup.tui;

import io.github.truscdo.foldbackup.core.PageSlice;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * TUI 共享控制台列表分页交互器（覆盖纯浏览、全局单选与跨页区间多选）。
 * <p>
 * 当总条目数不超过单页容量（{@code totalPages <= 1}）时退化为无翻页提示的单页输出，
 * 保持与既有小仓库交互体验和脚本序列完全一致。
 */
final class PagedConsoleList {

    static final int DEFAULT_PAGE_SIZE = 15;

    enum NavAction { NEXT, PREV, GOTO, SHOW_ALL, INVALID_GOTO, NONE }

    record NavCommand(NavAction action, int targetPage) {
        static final NavCommand NEXT = new NavCommand(NavAction.NEXT, 0);
        static final NavCommand PREV = new NavCommand(NavAction.PREV, 0);
        static final NavCommand SHOW_ALL = new NavCommand(NavAction.SHOW_ALL, 0);
        static final NavCommand INVALID_GOTO = new NavCommand(NavAction.INVALID_GOTO, 0);
        static final NavCommand NONE = new NavCommand(NavAction.NONE, 0);
    }

    /** 分页单选结果（{@code index} 为 1-based 全局编号）。 */
    record SingleSelection(int index, int currentPage, boolean eof, boolean back) {}

    /** 分页多选结果（保留当前所在页码）。 */
    record MultiPageSelection(ConsolePrompt.MultiSelection selection, int currentPage) {}

    private PagedConsoleList() {
    }

    static NavCommand parseNavCommand(String raw, boolean allowShowAll) {
        if (raw == null) {
            return NavCommand.NONE;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return NavCommand.NONE;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if ("n".equals(lower)) {
            return NavCommand.NEXT;
        }
        if ("p".equals(lower)) {
            return NavCommand.PREV;
        }
        if (allowShowAll && "a".equals(lower)) {
            return NavCommand.SHOW_ALL;
        }
        if ("g".equals(lower)) {
            return NavCommand.INVALID_GOTO;
        }
        if (lower.startsWith("g") && (Character.isWhitespace(lower.charAt(1)) || !Character.isAlphabetic(lower.charAt(1)))) {
            String rest = lower.substring(1).trim();
            try {
                int page = Integer.parseInt(rest);
                if (page >= 1) {
                    return new NavCommand(NavAction.GOTO, page);
                }
            } catch (NumberFormatException ignored) {
            }
            return NavCommand.INVALID_GOTO;
        }
        return NavCommand.NONE;
    }

    /** 纯浏览分页：单页直接展示并返回；多页支持 {@code n / p / g <page> / a / 0 / Enter}。 */
    static <T> void browse(
            ConsolePrompt prompt,
            PrintStream out,
            List<T> items,
            int pageSize,
            Consumer<PageSlice<T>> headerPrinter,
            BiConsumer<PageSlice<T>, PrintStream> pageRenderer,
            Consumer<PrintStream> allRenderer) {
        validateInputs(prompt, out, items, pageSize);
        int totalPages = PageSlice.totalPages(items.size(), pageSize);
        if (totalPages <= 1) {
            PageSlice<T> slice = PageSlice.of(items, 1, pageSize);
            headerPrinter.accept(slice);
            allRenderer.accept(out);
            out.println();
            return;
        }

        int currentPage = 1;
        while (true) {
            PageSlice<T> slice = PageSlice.of(items, currentPage, pageSize);
            headerPrinter.accept(slice);
            pageRenderer.accept(slice, out);
            out.println();

            while (true) {
                String line = prompt.readLine(formatBrowsePrompt(slice));
                if (line == null) {
                    return;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || "0".equals(trimmed)) {
                    out.println();
                    return;
                }
                NavCommand cmd = parseNavCommand(trimmed, true);
                switch (cmd.action()) {
                    case NEXT -> {
                        if (!slice.hasNext()) {
                            out.println("Already on the last page (" + slice.totalPages() + ").");
                            continue;
                        }
                        currentPage++;
                    }
                    case PREV -> {
                        if (!slice.hasPrevious()) {
                            out.println("Already on the first page.");
                            continue;
                        }
                        currentPage--;
                    }
                    case GOTO -> {
                        if (cmd.targetPage() > slice.totalPages()) {
                            out.println("Page must be between 1 and " + slice.totalPages() + ".");
                            continue;
                        }
                        currentPage = cmd.targetPage();
                    }
                    case SHOW_ALL -> {
                        headerPrinter.accept(PageSlice.of(items, 1, items.size()));
                        allRenderer.accept(out);
                        out.println();
                        return;
                    }
                    case INVALID_GOTO -> {
                        out.println("Please enter a valid page number between 1 and " + slice.totalPages() + ".");
                        continue;
                    }
                    case NONE -> {
                        out.println("Invalid command. Use " + navActionsText(slice, true) + ".");
                        continue;
                    }
                }
                break;
            }
        }
    }

    /** 分页单选：支持翻页与跨页直接输入全局 1-based 行号。 */
    static <T> SingleSelection selectSingle(
            ConsolePrompt prompt,
            PrintStream out,
            List<T> items,
            int pageSize,
            int initialPage,
            String singlePagePrompt,
            String multiPagePromptPrefix,
            BiConsumer<PageSlice<T>, PrintStream> pageRenderer) {
        validateInputs(prompt, out, items, pageSize);
        int totalPages = PageSlice.totalPages(items.size(), pageSize);
        if (totalPages <= 1) {
            PageSlice<T> slice = PageSlice.of(items, 1, pageSize);
            out.println();
            pageRenderer.accept(slice, out);
            out.println();
            int choice = prompt.readInt(singlePagePrompt, 0, items.size());
            if (choice == ConsolePrompt.EOF) {
                return new SingleSelection(0, 1, true, false);
            }
            if (choice <= 0) {
                return new SingleSelection(0, 1, false, true);
            }
            return new SingleSelection(choice, 1, false, false);
        }

        int currentPage = clampPage(initialPage, totalPages);
        while (true) {
            PageSlice<T> slice = PageSlice.of(items, currentPage, pageSize);
            out.println();
            printSelectionPageHeader(slice, out);
            pageRenderer.accept(slice, out);
            out.println();

            while (true) {
                String line = prompt.readLine(formatSingleSelectionPrompt(multiPagePromptPrefix, slice));
                if (line == null) {
                    return new SingleSelection(0, currentPage, true, false);
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || "0".equals(trimmed)) {
                    return new SingleSelection(0, currentPage, false, true);
                }
                NavCommand cmd = parseNavCommand(trimmed, false);
                Integer nextPage = handleSelectionNav(cmd, slice, out);
                if (nextPage != null) {
                    if (nextPage != currentPage) {
                        currentPage = nextPage;
                        break;
                    }
                    continue;
                }
                try {
                    int choice = Integer.parseInt(trimmed);
                    if (choice >= 1 && choice <= items.size()) {
                        return new SingleSelection(choice, currentPage, false, false);
                    }
                } catch (NumberFormatException ignored) {
                }
                out.println("Please enter a number between 1 and " + items.size()
                        + ", or " + navActionsText(slice, false) + ".");
            }
        }
    }

    /** 分页多选：支持翻页及跨页单号/闭区间输入（如 {@code 12,16-20}）。 */
    static <T> MultiPageSelection selectMultiple(
            ConsolePrompt prompt,
            PrintStream out,
            List<T> items,
            int pageSize,
            int initialPage,
            String singlePagePrompt,
            BiConsumer<PageSlice<T>, PrintStream> pageRenderer) {
        validateInputs(prompt, out, items, pageSize);
        int totalPages = PageSlice.totalPages(items.size(), pageSize);
        if (totalPages <= 1) {
            PageSlice<T> slice = PageSlice.of(items, 1, pageSize);
            out.println();
            pageRenderer.accept(slice, out);
            out.println();
            return new MultiPageSelection(prompt.readMultiSelection(singlePagePrompt, items.size()), 1);
        }

        int currentPage = clampPage(initialPage, totalPages);
        while (true) {
            PageSlice<T> slice = PageSlice.of(items, currentPage, pageSize);
            out.println();
            printSelectionPageHeader(slice, out);
            pageRenderer.accept(slice, out);
            out.println();

            while (true) {
                String line = prompt.readLine(formatMultiSelectionPrompt(singlePagePrompt, slice));
                if (line == null) {
                    return new MultiPageSelection(ConsolePrompt.MultiSelection.EOF_RESULT, currentPage);
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || "0".equals(trimmed)) {
                    return new MultiPageSelection(ConsolePrompt.MultiSelection.BACK_RESULT, currentPage);
                }
                NavCommand cmd = parseNavCommand(trimmed, false);
                Integer nextPage = handleSelectionNav(cmd, slice, out);
                if (nextPage != null) {
                    if (nextPage != currentPage) {
                        currentPage = nextPage;
                        break;
                    }
                    continue;
                }
                try {
                    return new MultiPageSelection(
                            ConsolePrompt.parseMultiSelection(trimmed, items.size()),
                            currentPage);
                } catch (IllegalArgumentException e) {
                    out.println("Invalid selection. Enter numbers or ranges between 1 and " + items.size()
                            + " (e.g. 5, 4-5, 2,4-5), or " + navActionsText(slice, false) + ".");
                }
            }
        }
    }

    /**
     * 处理选择模式下的翻页指令。
     * 返回非 {@code null} 表示已识别为翻页相关指令（值等于原页码表示越界停留原地提示，否则跳转至新页码）；
     * 返回 {@code null} 表示非翻页指令，交给编号解析处理。
     */
    private static Integer handleSelectionNav(NavCommand cmd, PageSlice<?> slice, PrintStream out) {
        return switch (cmd.action()) {
            case NEXT -> {
                if (!slice.hasNext()) {
                    out.println("Already on the last page (" + slice.totalPages() + ").");
                    yield slice.page();
                }
                yield slice.page() + 1;
            }
            case PREV -> {
                if (!slice.hasPrevious()) {
                    out.println("Already on the first page.");
                    yield slice.page();
                }
                yield slice.page() - 1;
            }
            case GOTO -> {
                if (cmd.targetPage() > slice.totalPages()) {
                    out.println("Page must be between 1 and " + slice.totalPages() + ".");
                    yield slice.page();
                }
                yield cmd.targetPage();
            }
            case INVALID_GOTO -> {
                out.println("Please enter a valid page number between 1 and " + slice.totalPages() + ".");
                yield slice.page();
            }
            case SHOW_ALL, NONE -> null;
        };
    }

    private static void printSelectionPageHeader(PageSlice<?> slice, PrintStream out) {
        out.println("Page " + slice.page() + "/" + slice.totalPages()
                + " (showing #" + slice.startIndex() + "-#" + slice.endIndex() + " of " + slice.totalItems() + "):");
    }

    private static String formatBrowsePrompt(PageSlice<?> slice) {
        return "Page " + slice.page() + "/" + slice.totalPages()
                + " (" + navActionsText(slice, true) + "): ";
    }

    private static String formatSingleSelectionPrompt(String prefix, PageSlice<?> slice) {
        return prefix + " (1-" + slice.totalItems() + "; " + navActionsText(slice, false) + "): ";
    }

    private static String formatMultiSelectionPrompt(String singlePagePrompt, PageSlice<?> slice) {
        int exampleIdx = singlePagePrompt.indexOf(" (e.g. ");
        int backIdx = singlePagePrompt.lastIndexOf("; 0 = back):");
        if (exampleIdx >= 0 && backIdx > exampleIdx) {
            String action = singlePagePrompt.substring(0, exampleIdx);
            String example = singlePagePrompt.substring(exampleIdx + " (".length(), backIdx);
            return action + " (1-" + slice.totalItems() + ", " + example
                    + "; " + navActionsText(slice, false) + "): ";
        }
        String base = singlePagePrompt.endsWith(": ")
                ? singlePagePrompt.substring(0, singlePagePrompt.length() - 2)
                : singlePagePrompt;
        return base + " (1-" + slice.totalItems() + "; " + navActionsText(slice, false) + "): ";
    }

    private static String navActionsText(PageSlice<?> slice, boolean allowShowAll) {
        StringBuilder sb = new StringBuilder();
        if (slice.hasPrevious()) {
            sb.append("p = prev");
        }
        if (slice.hasNext()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append("n = next");
        }
        if (!sb.isEmpty()) {
            sb.append(", ");
        }
        sb.append("g <page> = go");
        if (allowShowAll) {
            sb.append(", a = show all, 0/Enter = back");
        } else {
            sb.append(", 0 = back");
        }
        return sb.toString();
    }

    private static int clampPage(int page, int totalPages) {
        return Math.max(1, Math.min(page, totalPages));
    }

    private static <T> void validateInputs(ConsolePrompt prompt, PrintStream out, List<T> items, int pageSize) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1: " + pageSize);
        }
    }
}
