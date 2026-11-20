package io.github.truscdo.foldbackup.core;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * 异步工作线程异常解包与等待工具：透传 {@link Error}、{@link RuntimeException} 与 {@link IOException}，
 * 仅将其他受检异常（或空原因）包装为带上下文的 {@link IOException}。
 */
public final class Rethrow {

    private Rethrow() {
    }

    /**
     * 等待 {@code future} 完成并返回结果；中断时恢复当前线程中断标志并抛出 {@link IOException}，
     * 执行失败时按 {@link #asIo(Throwable, String)} 规范化原因后抛出。
     */
    public static <T> T awaitIo(Future<T> future, String interruptMessage, String failureContext)
            throws IOException {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(interruptMessage, "interruptMessage");
        Objects.requireNonNull(failureContext, "failureContext");
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(interruptMessage, e);
        } catch (ExecutionException e) {
            throw asIo(e.getCause(), failureContext);
        }
    }

    /**
     * 规范化异步任务抛出的原因：{@link Error} 与 {@link RuntimeException} 原样重抛，
     * {@link IOException} 原样返回，其余情况（含 {@code null}）返回带上下文的 {@link IOException}。
     */
    public static IOException asIo(Throwable cause, String context) {
        Objects.requireNonNull(context, "context");
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof IOException io) {
            return io;
        }
        return cause == null ? new IOException(context) : new IOException(context, cause);
    }
}
