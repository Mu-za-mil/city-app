package com.cityapp.auth.mapper;

import com.cityapp.auth.dto.RegisterRequest;
import com.cityapp.auth.dto.UserResponse;
import com.cityapp.auth.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for User ↔ DTO conversion.
 *
 * HOW MAPSTRUCT WORKS:
 *   At compile time, MapStruct reads this interface and generates:
 *   UserMapperImpl.java
 *
 *   The generated code looks like:
 *   @Override
 *   public UserResponse toResponse(User user) {
 *       if (user == null) return null;
 *       UserResponse.UserResponseBuilder builder = UserResponse.builder();
 *       builder.id(user.getId());
 *       builder.name(user.getName());
 *       builder.email(user.getEmail());
 *       ... // all matching fields
 *       return builder.build();
 *   }
 *
 *   This is exactly the code you would write manually.
 *   MapStruct writes it for you. Zero reflection. Same performance.
 *   If you rename a field: compile-time error, not a runtime NPE.
 *
 * componentModel = "spring":
 *   Makes the generated mapper a Spring @Component.
 *   Can be @Autowired into services.
 *   Without this: must use Mappers.getMapper(UserMapper.class) — not Spring-managed.
 *
 * @Mapping(target = "...", ignore = true):
 *   Tell MapStruct to skip this field.
 *   passwordHash is on the entity but not on UserResponse.
 *   MapStruct would warn: "Unmapped target property: passwordHash".
 *   ignore = true: suppress the warning, explicitly say "don't map this".
 *
 * @Mapping(source = "email", target = "username"):
 *   When field names differ between source and target.
 *   Not needed here — all field names match.
 */
@Mapper(componentModel = "spring")
public interface UserMapper {

    /**
     * Convert User entity → UserResponse DTO.
     * Called after saving a user or loading from DB.
     */
    UserResponse toResponse(User user);

    /**
     * Convert RegisterRequest → User entity.
     * Called in UserService.register() to create a new User.
     *
     * @Mapping ignore for passwordHash:
     *   RegisterRequest.password is plaintext.
     *   We BCrypt-hash it in the service before setting it.
     *   We don't want MapStruct trying to map password → passwordHash.
     *   (It can't anyway — different names — but be explicit.)
     *
     * @Mapping ignore for id, createdAt, updatedAt:
     *   These are set by the database on INSERT.
     *   MapStruct should not try to set them from the request.
     */
    @Mapping(target = "passwordHash",      ignore = true)
    @Mapping(target = "id",                ignore = true)
    @Mapping(target = "createdAt",         ignore = true)
    @Mapping(target = "updatedAt",         ignore = true)
    @Mapping(target = "enabled",           ignore = true)
    @Mapping(target = "accountNonLocked",  ignore = true)
    @Mapping(target = "profileImageUrl",   ignore = true)
    User toEntity(RegisterRequest request);
}
