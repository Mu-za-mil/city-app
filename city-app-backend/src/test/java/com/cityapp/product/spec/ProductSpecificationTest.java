package com.cityapp.product.spec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductSpecificationTest {

    @Test
    void buildsCaseInsensitiveContainsPattern() {
        assertEquals("%fresh milk%", ProductSpecification.buildKeywordPattern("Fresh Milk"));
    }

    @Test
    void escapesLikeWildcardsAndEscapeCharacterAsLiterals() {
        String escape = Character.toString((char) 92);
        String expected = "%" + "50" + escape + "%" + escape + "_" + " "
                + escape + escape + "rice" + "%";

        assertEquals(expected, ProductSpecification.buildKeywordPattern("50%_ \\RICE"));
    }
}
