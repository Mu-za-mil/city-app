package com.cityapp.common.response;

import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable pagination wrapper. Replaces Spring's Page<T> in all API responses.
 *
 * WHY NOT USE Spring's Page<T> DIRECTLY:
 *
 * Problem 1 — Spring's Page format changes between versions:
 *   Spring Boot 2.x: { "content": [...], "totalElements": 20, "pageable": {...} }
 *   Spring Boot 3.x: slightly different internal structure
 *   If you depend on the format: upgrade Spring = breaking frontend change.
 *
 * Problem 2 — Spring's Page<T> cannot be cached in Redis:
 *   @Cacheable on a Page<T> returning method will FAIL.
 *   Reason: Page<T> has no no-arg constructor. Jackson cannot deserialize it.
 *   Second request to same endpoint: ClassCastException.
 *   (This is the bug we hit in production. Now we document it upfront.)
 *
 * Problem 3 — Spring's Page contains internal metadata:
 *   "pageable": { "sort": {...}, "offset": 0, "pageNumber": 0, ... }
 *   Frontend doesn't need this. It clutters the response.
 *
 * OUR PAGERESPONSE:
 *   Clean, minimal, stable. Exactly what the frontend needs.
 *   { "content": [...], "page": 0, "size": 20, "totalElements": 347, "totalPages": 18, "last": false }
 *
 * RULE: NEVER cache Page<T> with @Cacheable.
 *       NEVER return Spring's Page<T> from a controller method.
 *       ALWAYS use PageResponse<T>.
 */
@Getter
@Builder
public class PageResponse<T> {

    private final List<T>  content;
    private final int      page;
    private final int      size;
    private final long     totalElements;
    private final int      totalPages;
    private final boolean  last;
    private final boolean  first;

    /**
     * Convert Spring's Page<T> to our stable PageResponse<T>.
     * Usage: return PageResponse.from(productRepository.findAll(spec, pageable));
     *
     * The .map() is for content type conversion:
     * PageResponse.from(page.map(entity -> mapper.toResponse(entity)))
     */
    public static <T> PageResponse<T> from(Page<T> page) {
        return PageResponse.<T>builder()
                .content(page.getContent())
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .first(page.isFirst())
                .build();
    }
}