package io.github.truscdo.foldbackup.tui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * TUI 控制台输入工具：编号选择与行读取，统一 EOF 语义。
 * <p>
 * 整个 TUI（含归档子菜单）共享一个实例，避免多处包装 {@code System.in}
 * 造成的缓冲竞争（原 {@code BackupTui.SCANNER} 的职责）。
 * <ul>
 * <li>{@link #readInt}：空输入返回 {@code -1}（调用方视为取消）；
 * EOF（stdin 关闭）返回 {@link Integer#MIN_VALUE}（调用方优雅退出，
 * 顶层菜单打印 {@code Goodbye!}）。越界/非数字在原地重试。</li>
 * <li>{@link #readLine}：EOF 返回 {@code null}。</li>
 * </ul>
 */
public final class ConsolePrompt {

    public static final int EOF = Integer.MIN_VALUE;
    public static final int BACK = -1;

    private final BufferedReader in;
    private final PrintStream out;

    public ConsolePrompt(Readable in, PrintStream out) {
        this.in = new BufferedReader(toReader(in));
        this.out = out;
    }

    /** {@link BufferedReader} 只接受 {@link Reader}，为任意 {@link Readable} 提供适配。 */
    private static Reader toReader(Readable in) {
        if (in instanceof Reader r) {
            return r;
        }
        return new Reader() {
            @Override
            public int read(char[] cbuf, int off, int len) throws IOException {
                CharBuffer cb = CharBuffer.wrap(cbuf, off, len);
                int n = in.read(cb);
                if (n < 0) {
                    return -1;
                }
                return cb.position() - off; // 追加的字符数
            }

            @Override
            public void close() {
                // Readable 无关闭语义
            }
        };
    }

    /**
     * 读取一个 {@code [min, max]} 内的整数。
     * 空输入返回 {@code -1}；EOF 返回 {@link #EOF}。
     */
    public int readInt(String prompt, int min, int max) {
        while (true) {
            out.print(prompt);
            String line = readLineRaw();
            if (line == null) {
                return EOF;
            }
            line = line.trim();
            if (line.isEmpty()) {
                return -1;
            }
            try {
                int v = Integer.parseInt(line);
                if (v >= min && v <= max) {
                    return v;
                }
                out.println("Please enter a number between " + min + " and " + max + ".");
            } catch (NumberFormatException e) {
                out.println("Please enter a number.");
            }
        }
    }

    /** 读取一行并返回原始内容（未 trim）；EOF 返回 {@code null}。 */
    public String readLine(String prompt) {
        out.print(prompt);
        return readLineRaw();
    }

    /** Blank selects automatic sizing (0); explicit 0 goes back; EOF is reported to the caller. */
    public int readThreadCount() {
        while (true) {
            String text = readLine("Threads (Enter for auto, 0 = back): ");
            if (text == null) return EOF;
            text = text.trim();
            if (text.isEmpty()) return 0;
            if ("0".equals(text)) return BACK;
            try {
                int threads = Integer.parseInt(text);
                if (threads > 0) return threads;
            } catch (NumberFormatException ignored) { }
            out.println("Threads must be a positive integer.");
        }
    }

    /** 多选编号输入结果（区分 EOF、返回与去重升序编号列表）。 */
    public record MultiSelection(boolean eof, boolean back, List<Integer> indices) {
        public static final MultiSelection EOF_RESULT = new MultiSelection(true, false, List.of());
        public static final MultiSelection BACK_RESULT = new MultiSelection(false, true, List.of());

        public MultiSelection {
            indices = List.copyOf(indices);
        }

        public static MultiSelection of(List<Integer> indices) {
            return new MultiSelection(false, false, indices);
        }
    }

    /**
     * 解析逗号分隔的单号与闭区间组合（如 {@code 5}、{@code 4-5}、{@code 2,4-5}）。
     * 空输入或单独输入 {@code 0} 返回 {@link MultiSelection#BACK_RESULT}；
     * 非法、越界或逆序区间抛出 {@link IllegalArgumentException}。
     */
    public static MultiSelection parseMultiSelection(String raw, int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be >= 1");
        }
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty() || "0".equals(trimmed)) {
            return MultiSelection.BACK_RESULT;
        }
        String[] parts = trimmed.split(",", -1);
        Set<Integer> selected = new TreeSet<>();
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Empty selection item");
            }
            if (token.contains("-")) {
                String[] bounds = token.split("-", -1);
                if (bounds.length != 2) {
                    throw new IllegalArgumentException("Invalid range: " + token);
                }
                int start = Integer.parseInt(bounds[0].trim());
                int end = Integer.parseInt(bounds[1].trim());
                if (start < 1 || end > max || start > end) {
                    throw new IllegalArgumentException("Range out of bounds: " + token);
                }
                for (int i = start; i <= end; i++) {
                    selected.add(i);
                }
            } else {
                int value = Integer.parseInt(token);
                if (value < 1 || value > max) {
                    throw new IllegalArgumentException("Number out of bounds: " + value);
                }
                selected.add(value);
            }
        }
        return MultiSelection.of(new ArrayList<>(selected));
    }

    /**
     * 读取多选编号输入（支持逗号分隔的单号与闭区间）；空输入或 {@code 0} 返回 BACK，EOF 返回 EOF，
     * 非法输入在原地提示重试。
     */
    public MultiSelection readMultiSelection(String prompt, int max) {
        while (true) {
            out.print(prompt);
            String line = readLineRaw();
            if (line == null) {
                return MultiSelection.EOF_RESULT;
            }
            try {
                return parseMultiSelection(line, max);
            } catch (IllegalArgumentException e) {
                out.println("Invalid selection. Enter numbers or ranges between 1 and " + max
                        + " (e.g. 5, 4-5, 2,4-5), or 0 to go back.");
            }
        }
    }

    private String readLineRaw() {
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
