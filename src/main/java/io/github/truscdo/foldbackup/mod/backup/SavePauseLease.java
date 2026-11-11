package io.github.truscdo.foldbackup.mod.backup;

/**
 * 世界保存暂停期间的存活凭证。
 * <p>
 * 备份开始前关闭自动保存，扫描期间由备份线程不断续期；服务器线程按
 * {@link #expired(long, long)} 判断是否长时间没有任何进展。租约只在
 * {@linkplain #begin() 开始} 到 {@linkplain #end() 结束} 之间生效，结束后
 * 的续期和判断都是空操作。
 * <p>
 * 时间戳统一使用 {@link System#currentTimeMillis()}，测试可直接传入构造出来的时刻。
 */
final class SavePauseLease {

    private volatile boolean active;
    private volatile long lastActivityMillis;

    /** 开始一次暂停；计时从此刻算起。 */
    void begin(long nowMillis) {
        lastActivityMillis = nowMillis;
        active = true;
    }

    /** 记录一次扫描进展；暂停已结束时忽略。 */
    void activity(long nowMillis) {
        if (active)
            lastActivityMillis = nowMillis;
    }

    /** 结束暂停（扫描完成或备份已被中止）。 */
    void end() {
        active = false;
    }

    boolean isActive() {
        return active;
    }

    /** 最后一次进展的时刻；未开始过时为 0。 */
    long lastActivityMillis() {
        return lastActivityMillis;
    }

    /**
     * 暂停仍在生效且距上次进展已达到阈值时返回 {@code true}。
     *
     * @param timeoutMillis 阈值；非正数表示不做限制
     */
    boolean expired(long nowMillis, long timeoutMillis) {
        return timeoutMillis > 0 && active && nowMillis - lastActivityMillis >= timeoutMillis;
    }
}
