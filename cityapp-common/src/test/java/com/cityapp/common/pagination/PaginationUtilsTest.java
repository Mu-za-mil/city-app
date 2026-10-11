package com.cityapp.common.pagination;

import com.cityapp.common.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaginationUtilsTest {

    @Test
    void appliesRequestedPageAndSortWhenWithinLimits() {
        var pageable = PaginationUtils.of(2, 50, Sort.by("createdAt").descending());

        assertEquals(2, pageable.getPageNumber());
        assertEquals(50, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("createdAt").getDirection());
    }

    @Test
    void capsPageSizeAtMaximum() {
        var pageable = PaginationUtils.of(0, 10_000, Sort.unsorted());

        assertEquals(PaginationUtils.MAX_PAGE_SIZE, pageable.getPageSize());
    }

    @Test
    void rejectsNegativePageIndex() {
        var ex = assertThrows(AppException.class,
                () -> PaginationUtils.of(-1, PaginationUtils.DEFAULT_SIZE, Sort.unsorted()));

        assertEquals(400, ex.getStatus().value());
    }

    @Test
    void rejectsZeroOrNegativePageSize() {
        assertThrows(AppException.class, () -> PaginationUtils.of(0, 0, Sort.unsorted()));
        assertThrows(AppException.class, () -> PaginationUtils.of(0, -1, Sort.unsorted()));
    }
}
