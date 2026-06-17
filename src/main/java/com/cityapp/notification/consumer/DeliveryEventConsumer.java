package com.cityapp.notification.consumer;

import com.cityapp.common.constants.AppConstants;
import com.cityapp.common.event.DeliveryAssignedEvent;
import com.cityapp.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryEventConsumer {

    private final NotificationService notificationService;

    @KafkaListener(
            topics  = AppConstants.TOPIC_DELIVERY_ASSIGNED,
            groupId = "notification-service-delivery-assigned"
    )
    public void onDeliveryAssigned(@Payload DeliveryAssignedEvent event) {
        log.info("Consuming delivery.assigned: orderId={} partnerId={}",
                event.getOrderId(), event.getPartnerId());

        notificationService.sendDeliveryAssignedNotifications(event);
    }
}
