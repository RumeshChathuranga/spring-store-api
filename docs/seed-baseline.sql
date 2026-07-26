-- Seed data required by docs/api-baseline.http
--
-- Two things cannot be created through the REST API and must be seeded here:
--
--   1. CATEGORIES. There is no category endpoint anywhere in the codebase, but
--      POST /products returns 400 unless productDto.categoryId matches an
--      existing row (ProductController.createProduct does
--      categoryRepository.findById(...).orElse(null) and bails on null).
--
--   2. AN ADMIN USER. UserService.registerUser hardcodes user.setRole(Role.USER),
--      so the /products write endpoints and /admin/** are unreachable through
--      the API alone. The admin must be promoted with SQL.
--
-- Usage:
--   1. Start the app once so Flyway creates the schema.
--   2. Run the "Register admin user" request in docs/api-baseline.http.
--   3. mysql -u root -p store_api < docs/seed-baseline.sql
--   4. Run the rest of the collection.
--
-- Safe to re-run.

-- ---------------------------------------------------------------------------
-- Categories
-- ---------------------------------------------------------------------------
INSERT INTO categories (id, name) VALUES
    (1, 'Electronics'),
    (2, 'Books'),
    (3, 'Groceries')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- ---------------------------------------------------------------------------
-- Promote the baseline admin account
-- ---------------------------------------------------------------------------
UPDATE users
SET role = 'ADMIN'
WHERE email = 'admin@baseline.test';

-- ---------------------------------------------------------------------------
-- Verify
-- ---------------------------------------------------------------------------
SELECT id, name FROM categories;
SELECT id, email, role FROM users WHERE email LIKE '%@baseline.test';
