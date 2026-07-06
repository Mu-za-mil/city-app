package com.cityapp.common.event;

import com.cityapp.user.entity.Role;
import lombok.*;
import java.time.Instant;

@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class UserRegisteredEvent {

    private String  eventId;
    private Long    userId;
    private String  email;
    private String  name;
    private String  phone;
    private Role    role;
    private Instant registeredAt;
}
