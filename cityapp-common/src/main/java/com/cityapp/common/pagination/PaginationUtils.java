package com.cityapp.common.pagination;

import com.cityapp.common.exception.AppException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Shared pagination policy for paginated HTTP endpoints.
 *
 * Page numbering is zero-based. Requests for more than {@value #MAX_PAGE_SIZE}
 * items are capped so clients cannot accidentally trigger unbounded database reads.
 * Invalid negative pages and non-positive sizes are rejected as bad requests.
 */
public final class PaginationUtils {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    public static final String DEFAULT_PAGE_VALUE = "0";
    public static final String DEFAULT_SIZE_VALUE = "20";

    private PaginationUtils() {
    }

    public static Pageable of(int page, int size, Sort sort) {
        if (page < 0) {
            throw AppException.badRequest("Page index must be zero or greater");
        }
        if (size < 1) {
            throw AppException.badRequest("Page size must be at least 1");
        }

        int boundedSize = Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(page, boundedSize, sort == null ? Sort.unsorted() : sort);
    }
}
