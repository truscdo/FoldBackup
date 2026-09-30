package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 快照 ID 参数的 {@code SuggestionProvider}。
 * 提供现有快照 ID 作为 Tab 补全候选项，
 * 按前缀过滤并限制最大数量。
 */
public class SnapshotIdSuggestions {

    /** 每次按 Tab 返回的最大建议数。 */
    private static final int MAX_SUGGESTIONS = 20;

    /**
     * 返回一个 {@link SuggestionProvider}，列出与用户输入的前缀匹配的快照 ID，
     * 按最新优先排序，最多 {@value #MAX_SUGGESTIONS} 条结果。
     * <p>
     * 当输入为空时返回最近的快照；每次补全时获取后端，支持命令注册后的初始化。
     * 使用 {@code startsWith} 进行前缀匹配，与 SQL {@code LIKE 'prefix%'} 一致。
     *
     * @param snapshotStore 后端提供者（如果后端未初始化可以返回 {@code null}）
     * @return 快照 ID 参数的建议提供者
     */
    public static SuggestionProvider<CommandSourceStack> suggestAll(Supplier<SnapshotStore> snapshotStore) {
        return (ctx, builder) -> {
            SnapshotStore snapshotManager = snapshotStore.get();
            if (snapshotManager == null) {
                return builder.buildFuture();
            }
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            // listSnapshots() 返回最新优先；前缀过滤 + LIMIT
            List<String> ids = snapshotManager.listSnapshots();
            int count = 0;
            for (String id : ids) {
                if (id.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(id);
                    count++;
                    if (count >= MAX_SUGGESTIONS) {
                        break;
                    }
                }
            }
            return builder.buildFuture();
        };
    }
}
