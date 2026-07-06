package com.cityapp.category.service;

import com.cityapp.category.entity.Category;
import com.cityapp.category.repository.CategoryRepository;
import com.cityapp.common.constants.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;

    /**
     * The category list is the PERFECT candidate for aggressive caching:
     *   - Read: on every page load that shows category filters (thousands/minute)
     *   - Write: almost never (admin adds a category maybe once a month)
     *   - Size: 16 rows, ~2KB of JSON
     *   - Consequence of staleness: very low (new category not visible for 1 hour)
     *
     * TTL = 1 hour: configured in RedisConfig.cacheManager()
     *
     * Cache key: "categories::active"
     * Simple fixed key: the list is the SAME for all users.
     * Not parameterised by userId or storeId.
     */
    @Cacheable(value = AppConstants.CACHE_CATEGORIES, key = "'active'")
    // key = "'active'" — literal string key, not a method parameter.
    // SpEL: #paramName refers to a method parameter.
    //       'literal' is a literal string.
    // Result: all callers get the same cached list.
    @Transactional(readOnly = true)
    public List<Category> getActiveCategories() {
        List<Category> categories = categoryRepository.findByActiveTrue();
        log.debug("Categories loaded from DB: {} categories", categories.size());
        return categories;
    }

    /**
     * When an admin adds or modifies a category:
     * evict the cached list so next request loads fresh data.
     */
    @CacheEvict(value = AppConstants.CACHE_CATEGORIES, allEntries = true)
    // allEntries = true: evict ALL entries in the "categories" cache.
    // We only have one entry ("categories::active") so this is equivalent to
    // @CacheEvict(value="categories", key="'active'").
    // allEntries = true is future-proof: if we add more category cache keys,
    // they all get evicted on any category modification.
    @Transactional
    public Category createCategory(String name, String slug, String iconUrl) {
        Category category = Category.builder()
                .name(name)
                .slug(slug)
                .iconUrl(iconUrl)
                .active(true)
                .build();
        return categoryRepository.save(category);
    }
}