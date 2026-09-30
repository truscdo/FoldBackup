package com.foldbackup.tui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConsolePrompt} 单元测试：注入式输入/输出下的编号选择与行读取，
 * 重点验证空输入（取消，返回 -1）与 EOF（返回 {@link Integer#MIN_VALUE}）
 * 的区分语义。
 */
class ConsolePromptTest {

    @Test
    void threadCountDistinguishesAutoAndBackAndRetriesInvalidInput() {
        var output = new ByteArrayOutputStream();
        var prompt = new ConsolePrompt(new StringReader("\n0\nbad\n-2\n2147483648\n3\n"),
                new PrintStream(output));
        assertEquals(0, prompt.readThreadCount());
        assertEquals(ConsolePrompt.BACK, prompt.readThreadCount());
        assertEquals(3, prompt.readThreadCount());
        assertEquals(3, output.toString().split("Threads must be a positive integer.", -1).length - 1);
    }

    @Test
    void readInt_validInput() {
        var out = new ByteArrayOutputStream();
        var prompt = new ConsolePrompt(new StringReader("3\n"), new PrintStream(out, true));
        assertEquals(3, prompt.readInt("Pick: ", 1, 5));
    }

    @Test
    void readInt_emptyInput_returnsMinusOne() {
        var out = new ByteArrayOutputStream();
        var prompt = new ConsolePrompt(new StringReader("\n"), new PrintStream(out, true));
        assertEquals(-1, prompt.readInt("Pick: ", 1, 5));
    }

    @Test
    void readInt_eof_returnsMinValue() {
        var out = new ByteArrayOutputStream();
        var prompt = new ConsolePrompt(new StringReader(""), new PrintStream(out, true));
        assertEquals(Integer.MIN_VALUE, prompt.readInt("Pick: ", 1, 5));
    }

    @Test
    void readInt_retriesUntilInRange() {
        var out = new ByteArrayOutputStream();
        var prompt = new ConsolePrompt(new StringReader("abc\n99\n2\n"),
                new PrintStream(out, true));
        assertEquals(2, prompt.readInt("Pick: ", 1, 5));
        String output = out.toString();
        assertTrue(output.contains("Please enter a number."));
        assertTrue(output.contains("Please enter a number between 1 and 5."));
    }

    @Test
    void readInt_eofAfterInvalidInput_stillEof() {
        var out = new ByteArrayOutputStream();
        var prompt = new ConsolePrompt(new StringReader("abc\n"), new PrintStream(out, true));
        assertEquals(Integer.MIN_VALUE, prompt.readInt("Pick: ", 1, 5));
    }

    @Test
    void readInt_zeroAllowedWhenInclusive() {
        var out = new ByteArrayOutputStream();
        var prompt = new ConsolePrompt(new StringReader("0\n"), new PrintStream(out, true));
        assertEquals(0, prompt.readInt("Diff (0 = back): ", 0, 3));
    }

    @Test
    void readLine_returnsTrimmed() {
        var out = new ByteArrayOutputStream();
        var prompt = new ConsolePrompt(new StringReader("some path\n"),
                new PrintStream(out, true));
        assertEquals("some path", prompt.readLine("Path: "));
    }

    @Test
    void readLine_eof_returnsNull() {
        var out = new ByteArrayOutputStream();
        var prompt = new ConsolePrompt(new StringReader(""), new PrintStream(out, true));
        assertNull(prompt.readLine("Path: "));
    }
}
