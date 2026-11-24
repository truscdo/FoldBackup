package io.github.truscdo.foldbackup.mod.command;

import io.github.truscdo.foldbackup.core.store.SnapshotManifest;
import io.github.truscdo.foldbackup.core.store.SnapshotStore;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 快照 ID 参数的 {@code SuggestionProvider}。
 * 提供现有快照 ID 作为 Tab 补全候选项，
 * 按前缀过滤并限制最大数量。
 */
public class SnapshotIdSuggestions {

    /** 每次按 Tab 返回的最大建议数。 */
    private static final int MAX_SUGGESTIONS = 20;
    private static final String DRY_RUN_FLAG = "--dry-run";

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

    /**
     * 专用于 {@code /foldbackup delete} 的连续 Tab 补全提供者：
     * 按最后一个空格切分词元并推进补全起点；自动过滤已输入的快照 ID 及处于 {@code [PIN]} 状态的快照，
     * 并在尚未输入 {@code --dry-run} 时提供 {@code --dry-run} 候选。
     */
    public static SuggestionProvider<CommandSourceStack> suggestForDelete(Supplier<SnapshotStore> snapshotStore) {
        return (ctx, builder) -> {
            SnapshotStore snapshotManager = snapshotStore.get();
            if (snapshotManager == null) {
                return builder.buildFuture();
            }
            String raw = builder.getRemaining();
            int lastSpace = raw.lastIndexOf(' ');
            int tokenStart = builder.getStart() + lastSpace + 1;
            String prefixPart = lastSpace >= 0 ? raw.substring(0, lastSpace) : "";
            String currentToken = lastSpace >= 0 ? raw.substring(lastSpace + 1) : raw;

            boolean hasDryRun = false;
            Set<String> usedIds = new HashSet<>();
            if (!prefixPart.isBlank()) {
                for (String token : prefixPart.trim().split("\\s+")) {
                    if (DRY_RUN_FLAG.equals(token)) {
                        hasDryRun = true;
                    } else {
                        usedIds.add(token.toLowerCase(Locale.ROOT));
                    }
                }
            }

            SuggestionsBuilder tokenBuilder = builder.createOffset(tokenStart);
            String remainingLower = currentToken.toLowerCase(Locale.ROOT);
            int count = 0;
            if (!hasDryRun && DRY_RUN_FLAG.startsWith(remainingLower)) {
                tokenBuilder.suggest(DRY_RUN_FLAG);
                count++;
            }
            for (SnapshotManifest snapshot : snapshotManager.loadAllSnapshots()) {
                if (count >= MAX_SUGGESTIONS) {
                    break;
                }
                if (snapshot.isPinned()) {
                    continue;
                }
                String id = snapshot.getSnapshotId();
                String idLower = id.toLowerCase(Locale.ROOT);
                if (usedIds.contains(idLower)) {
                    continue;
                }
                if (idLower.startsWith(remainingLower)) {
                    tokenBuilder.suggest(id);
                    count++;
                }
            }
            return tokenBuilder.buildFuture();
        };
    }
}
