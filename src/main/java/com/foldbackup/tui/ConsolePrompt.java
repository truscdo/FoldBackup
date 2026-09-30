package com.foldbackup.tui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.CharBuffer;

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

    private String readLineRaw() {
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
