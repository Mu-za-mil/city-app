-- ============================================================================
-- V2: Fix super admin password hash
-- Description: Updates the super admin user with correct BCrypt hash for 'Admin@1234'
-- ============================================================================

-- Update the password hash for super admin user
UPDATE users
SET password_hash = '$2a$12$LqhB20a3P6/5ONmph9vT2e9XBFCXtFwTlFtFWkjqDMl.9hnAajvyO'
WHERE email = 'admin@cityapp.com';

