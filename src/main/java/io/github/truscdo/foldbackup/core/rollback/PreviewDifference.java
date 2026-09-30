package io.github.truscdo.foldbackup.core.rollback;

import java.util.Map;
import java.util.Objects;

/** 数据差异统计：按身份与值比较，不依赖 Minecraft 注册表。 */
public final class PreviewDifference {
    private PreviewDifference() {}
    public record Counts(int added, int removed, int changed) {
        @Override public String toString() { return "+" + added + " / -" + removed + " / ~" + changed; }
    }
    public static <T> Counts blocks(T[] before, T[] after, java.util.function.Predicate<T> isAir) {
        if (before.length != after.length) throw new IllegalArgumentException("World heights differ");
        int added = 0, removed = 0, changed = 0;
        for (int i = 0; i < before.length; i++) {
            T a = before[i], b = after[i];
            if (Objects.equals(a, b)) continue;
            if (isAir.test(a) && !isAir.test(b)) added++;
            else if (!isAir.test(a) && isAir.test(b)) removed++;
            else changed++;
        }
        return new Counts(added, removed, changed);
    }
    public static <K, V> Counts counts(Map<K, V> before, Map<K, V> after) {
        int added = 0, removed = 0, changed = 0;
        for (K key : after.keySet()) {
            if (!before.containsKey(key)) added++;
            else if (!Objects.equals(before.get(key), after.get(key))) changed++;
        }
        for (K key : before.keySet()) if (!after.containsKey(key)) removed++;
        return new Counts(added, removed, changed);
    }
}
