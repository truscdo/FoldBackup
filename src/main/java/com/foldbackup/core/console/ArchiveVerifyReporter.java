package com.foldbackup.core.console;

import com.foldbackup.core.archive.ArchiveFormatUtil;
import com.foldbackup.core.archive.ArchiveVerifier;

import java.io.PrintStream;

/**
 * {@link ArchiveVerifier.Result} 的统一文本输出（CLI 与 TUI 共用）。
 * <p>
 * 输出格式：{@code Verified X .lrh, Y .lrhd, Z chunk hashes, N total} +
 * {@code OK} 或错误列表。失败后的退出策略由调用方决定（CLI {@code System.exit(1)}，
 * TUI 回菜单）。
 */
public final class ArchiveVerifyReporter {

    private ArchiveVerifyReporter() {
    }

    public static void print(ArchiveVerifier.Result r, PrintStream out) {
        out.println("Verified " + r.lrhChecked + " .lrh, " + r.lrhdChecked + " .lrhd, "
                + r.chunkHashesChecked + " chunk hashes, "
                + ArchiveFormatUtil.formatSize(r.totalBytes) + " total");
        if (r.ok()) {
            out.println("OK — no errors");
        } else {
            out.println("FAILED — " + r.errors.size() + " error(s):");
            for (String err : r.errors) {
                out.println("  " + err);
            }
        }
    }
}
