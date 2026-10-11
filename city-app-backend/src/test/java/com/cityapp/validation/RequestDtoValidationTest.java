package com.cityapp.validation;

import com.cityapp.cart.dto.AddToCartRequest;
import com.cityapp.cart.dto.CartCheckoutRequest;
import com.cityapp.order.dto.OrderItemRequest;
import com.cityapp.order.dto.PlaceOrderRequest;
import com.cityapp.order.entity.OrderType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestDtoValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    void rejectsMissingOrderFieldsAndInvalidNestedOrderItems() {
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setItems(List.of(new OrderItemRequest(0L, 0)));

        var violations = validator.validate(request);

        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("storeId")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("orderType")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("items[0].productId")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("items[0].quantity")));
    }

    @Test
    void acceptsOrderRequestWithRequiredFieldsAndValidItems() {
        PlaceOrderRequest request = new PlaceOrderRequest();
        request.setStoreId(1L);
        request.setOrderType(OrderType.TAKEAWAY);
        request.setItems(List.of(new OrderItemRequest(2L, 1)));

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void checkoutRequiresOrderTypeButDoesNotRequireBodyStoreId() {
        CartCheckoutRequest request = new CartCheckoutRequest();

        var violations = validator.validate(request);

        assertTrue(violations.stream().noneMatch(v -> v.getPropertyPath().toString().equals("storeId")));
        assertTrue(violations.stream().anyMatch(v -> v.getPropertyPath().toString().equals("orderType")));
    }

    @Test
    void rejectsInvalidCartProductIdAndQuantity() {
        AddToCartRequest request = new AddToCartRequest();
        request.setProductId(0L);
        request.setQuantity(100);

        assertFalse(validator.validate(request).isEmpty());
    }
}
