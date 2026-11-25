package io.github.truscdo.foldbackup.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link PageSlice} 单元测试。 */
class PageSliceTest {

    @Test
    void emptyListAllowsOnlyPageOne() {
        PageSlice<String> slice = PageSlice.of(List.of(), 1, 10);
        assertTrue(slice.isEmpty());
        assertFalse(slice.isMultiPage());
        assertFalse(slice.hasPrevious());
        assertFalse(slice.hasNext());
        assertEquals(1, slice.page());
        assertEquals(10, slice.pageSize());
        assertEquals(0, slice.totalItems());
        assertEquals(0, slice.totalPages());
        assertEquals(0, slice.startIndex());
        assertEquals(0, slice.endIndex());
        assertEquals(0, PageSlice.totalPages(0, 10));

        assertThrows(IllegalArgumentException.class, () -> PageSlice.of(List.of(), 2, 10));
        assertThrows(IllegalArgumentException.class, () -> PageSlice.of(List.of(), 0, 10));
    }

    @Test
    void singleAndExactMultiplePagesComputeBoundariesAccurately() {
        List<Integer> three = List.of(10, 20, 30);
        PageSlice<Integer> single = PageSlice.of(three, 1, 5);
        assertFalse(single.isMultiPage());
        assertFalse(single.hasPrevious());
        assertFalse(single.hasNext());
        assertEquals(1, single.totalPages());
        assertEquals(1, single.startIndex());
        assertEquals(3, single.endIndex());
        assertEquals(three, single.items());

        List<Integer> four = List.of(1, 2, 3, 4);
        assertEquals(2, PageSlice.totalPages(4, 2));
        PageSlice<Integer> exactFirst = PageSlice.of(four, 1, 2);
        assertTrue(exactFirst.isMultiPage());
        assertFalse(exactFirst.hasPrevious());
        assertTrue(exactFirst.hasNext());
        assertEquals(List.of(1, 2), exactFirst.items());
        assertEquals(1, exactFirst.startIndex());
        assertEquals(2, exactFirst.endIndex());

        PageSlice<Integer> exactLast = PageSlice.of(four, 2, 2);
        assertTrue(exactLast.hasPrevious());
        assertFalse(exactLast.hasNext());
        assertEquals(List.of(3, 4), exactLast.items());
        assertEquals(3, exactLast.startIndex());
        assertEquals(4, exactLast.endIndex());
    }

    @Test
    void partialLastPageAndOutOfRangeValidation() {
        List<String> items = List.of("a", "b", "c", "d", "e");
        assertEquals(3, PageSlice.totalPages(5, 2));

        PageSlice<String> middle = PageSlice.of(items, 2, 2);
        assertTrue(middle.hasPrevious());
        assertTrue(middle.hasNext());
        assertEquals(List.of("c", "d"), middle.items());
        assertEquals(3, middle.startIndex());
        assertEquals(4, middle.endIndex());

        PageSlice<String> tail = PageSlice.of(items, 3, 2);
        assertTrue(tail.hasPrevious());
        assertFalse(tail.hasNext());
        assertEquals(List.of("e"), tail.items());
        assertEquals(5, tail.startIndex());
        assertEquals(5, tail.endIndex());

        assertThrows(IllegalArgumentException.class, () -> PageSlice.of(items, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> PageSlice.of(items, 4, 2));
        assertThrows(IllegalArgumentException.class, () -> PageSlice.of(items, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> PageSlice.totalPages(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> PageSlice.totalPages(5, 0));
    }
}
