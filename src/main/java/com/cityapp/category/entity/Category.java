package com.cityapp.category.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "categories")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;
    // Slug: URL-friendly version. "Grocery & Supermarket" → "grocery"
    // Used in: GET /api/v1/stores?category=grocery (cleaner than category IDs in URLs)

    @Column(name = "icon_url")
    private String iconUrl;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
