SELECT COUNT(*) INTO @has_password_hash
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'user_account'
  AND column_name = 'password_hash';

SET @add_password_hash_sql = IF(
  @has_password_hash = 0,
  'ALTER TABLE user_account ADD COLUMN password_hash VARCHAR(255) NULL AFTER email',
  'DO 0'
);

PREPARE add_password_hash_stmt FROM @add_password_hash_sql;
EXECUTE add_password_hash_stmt;
DEALLOCATE PREPARE add_password_hash_stmt;
