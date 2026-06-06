package com.cityapp.product.spec;

import com.cityapp.product.entity.Product;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;

/**
 * JPA Specifications for dynamic product filtering.
 *
 * WHY JPA SPECIFICATIONS NOT NATIVE SQL:
 *
 *   NAIVE APPROACH (string building — NEVER DO THIS):
 *   String query = "SELECT * FROM products WHERE active=true";
 *   if (keyword != null) query += " AND name LIKE '%" + keyword + "%'";
 *   if (categoryId != null) query += " AND category_id = " + categoryId;
 *   if (minPrice != null) query += " AND price >= " + minPrice;
 *
 *   PROBLEMS:
 *   1. SQL INJECTION: keyword = "'; DROP TABLE products; --"
 *      Your products table is now gone.
 *   2. UNMAINTAINABLE: 5 filters = 32 possible combinations.
 *      Every combination needs testing.
 *   3. TYPE UNSAFE: string concatenation, no compiler checks.
 *
 *   JPA SPECIFICATIONS:
 *   Type-safe, composable, injection-proof.
 *   Each Specification is a function: (Root, Query, CriteriaBuilder) → Predicate
 *   Predicates are combined with AND/OR.
 *
 *   Specification.where(isActive())
 *     .and(withKeyword("rice"))
 *     .and(inCategory(1L))
 *     .and(priceBetween(50, 200))
 *
 *   JPA generates: WHERE active=true AND (LOWER(name) LIKE ?) AND category_id=? AND price BETWEEN ? AND ?
 *   With proper parameterised queries. SQL injection impossible.
 *
 * HOW SPECIFICATIONS WORK:
 *   Root<Product>: represents the FROM clause (the products table).
 *   CriteriaBuilder: builds predicates (WHERE conditions).
 *   Predicate: a single WHERE condition.
 *   Multiple predicates combined → the full WHERE clause.
 *
 *   These are Java 8 lambdas. Each method returns a lambda that
 *   Spring Data calls when executing the query.
 */
public class ProductSpecification {

    /**
     * Only return active products (soft-delete filter).
     * Applied to ALL product queries — buyers never see inactive products.
     */
    public static Specification<Product> isActive() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    /**
     * Filter by store.
     * Used for: "show all products in store X".
     */
    public static Specification<Product> inStore(Long storeId) {
        if (storeId == null) return Specification.allOf((Iterable<Specification<Product>>) null);
        return (root, query, cb) ->
                cb.equal(root.get("store").get("id"), storeId);
    }

    /**
     * Keyword search across name AND description.
     * Case-insensitive LIKE search.
     *
     * LOWER(name) LIKE LOWER('%rice%')
     *
     * WHY NOT full-text search here:
     * JPA Specifications don't natively support PostgreSQL FTS (to_tsvector).
     * For simple keyword search: LIKE is sufficient and readable.
     * For advanced FTS: use @Query with nativeQuery=true.
     * Phase 9 adds Elasticsearch for production-grade search.
     */
    public static Specification<Product> withKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return Specification.allOf((Iterable<Specification<Product>>) null);
        String pattern = "%" + keyword.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")),        pattern),
                cb.like(cb.lower(root.get("description")), pattern)
        );
    }

    /**
     * Filter by category.
     */
    public static Specification<Product> inCategory(Long categoryId) {
        if (categoryId == null) return Specification.allOf((Iterable<Specification<Product>>) null);
        return (root, query, cb) ->
                cb.equal(root.get("category").get("id"), categoryId);
    }

    /**
     * Price range filter.
     * minPrice and maxPrice are both optional — either can be null.
     */
    public static Specification<Product> priceBetween(
            BigDecimal minPrice, BigDecimal maxPrice) {
        return (root, query, cb) -> {
            Predicate predicate = cb.conjunction(); // starts as TRUE
            if (minPrice != null) {
                predicate = cb.and(predicate,
                        cb.greaterThanOrEqualTo(root.get("price"), minPrice));
            }
            if (maxPrice != null) {
                predicate = cb.and(predicate,
                        cb.lessThanOrEqualTo(root.get("price"), maxPrice));
            }
            return predicate;
        };
    }

    /**
     * Filter by availability (has stock).
     * Joins to inventory table.
     */
    public static Specification<Product> hasStock() {
        return (root, query, cb) -> {
            // JOIN products p ON p.id = i.product_id
            Join<Object, Object> inventory = root.join("inventory",
                    JoinType.INNER);
            return cb.greaterThan(inventory.get("quantity"), 0);
        };
    }
}
