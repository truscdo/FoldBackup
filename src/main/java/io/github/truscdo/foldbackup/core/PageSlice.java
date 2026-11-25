package io.github.truscdo.foldbackup.core;

import java.util.List;
import java.util.Objects;

/**
 * 列表分页切片模型（1-based 页码与全局行号）。
 */
public record PageSlice<T>(
        List<T> items,
        int page,
        int pageSize,
        int totalItems,
        int totalPages,
        int startIndex,
        int endIndex) {

    public PageSlice {
        items = List.copyOf(items);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public boolean isMultiPage() {
        return totalPages > 1;
    }

    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean hasNext() {
        return page < totalPages;
    }

    /** 计算给定总条数与页大小下的总页数；空列表返回 0。 */
    public static int totalPages(int totalItems, int pageSize) {
        if (totalItems < 0) {
            throw new IllegalArgumentException("totalItems must be >= 0: " + totalItems);
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1: " + pageSize);
        }
        return totalItems == 0 ? 0 : Math.ceilDiv(totalItems, pageSize);
    }

    /** 按 1-based 页码从全量列表中切片，校验页码与页大小边界。 */
    public static <T> PageSlice<T> of(List<T> allItems, int page, int pageSize) {
        Objects.requireNonNull(allItems, "allItems");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1: " + pageSize);
        }
        int total = allItems.size();
        int pages = totalPages(total, pageSize);
        if (total == 0) {
            if (page != 1) {
                throw new IllegalArgumentException("page must be 1 for empty list, got: " + page);
            }
            return new PageSlice<>(List.of(), 1, pageSize, 0, 0, 0, 0);
        }
        if (page < 1 || page > pages) {
            throw new IllegalArgumentException("page out of range: " + page + " (1.." + pages + ")");
        }
        int from = (page - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        return new PageSlice<>(allItems.subList(from, to), page, pageSize, total, pages, from + 1, to);
    }
}
