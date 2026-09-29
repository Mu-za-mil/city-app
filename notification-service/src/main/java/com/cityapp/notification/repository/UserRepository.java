package com.cityapp.notification.repository;


import com.cityapp.common.enums.Role;
import com.cityapp.notification.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Data access interface for the users table.
 *
 * WHY AN INTERFACE NOT A CLASS:
 *   Spring Data JPA generates the SQL implementation at runtime.
 *   You declare WHAT you want. Spring generates HOW to get it.
 *   findByEmail(String email) → Spring generates:
 *     SELECT * FROM users WHERE email = ? (with parameter binding)
 *
 *   This eliminates entire categories of bugs:
 *   - No string concatenation (SQL injection impossible)
 *   - No typos in column names (Spring validates at startup)
 *   - No missing null checks
 *
 * WHY JpaRepository<User, Long>:
 *   First type: the Entity (User)
 *   Second type: the type of the primary key (Long, because users.id is BIGSERIAL → Long)
 *
 *   JpaRepository provides free implementations of:
 *   save(user), findById(id), findAll(), deleteById(id), count(), exists(id)
 *   All generated. None written by you.
 *
 * DERIVED QUERY METHODS:
 *   findByEmail → SELECT * FROM users WHERE email = ?
 *   findByPhone → SELECT * FROM users WHERE phone = ?
 *   existsByEmail → SELECT COUNT(*) > 0 FROM users WHERE email = ?
 *   findByRole → SELECT * FROM users WHERE role = ?
 *
 *   Spring parses the method name and generates the SQL.
 *   No @Query annotation needed for simple conditions.
 *   @Query is needed for complex joins, native SQL, or multi-table queries.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    // Used by: login (find user to compare password), JWT filter (load user by email)
    Optional<User> findByEmail(String email);

    // Used by: OTP login
    Optional<User> findByPhone(String phone);

    // Used by: registration (check if email already taken before saving)
    boolean existsByEmail(String email);

    // Used by: registration (check if phone already registered)
    boolean existsByPhone(String phone);

    // Used by: admin panel (list all users with pagination)
    Page<User> findByRole(Role role, Pageable pageable);

    // Used by: admin panel (list all users regardless of role)
    // inherited from JpaRepository: findAll(Pageable pageable)
}