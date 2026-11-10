-- Development fixture only. Flyway intentionally does not provision production accounts.
-- Existing account balances are preserved if this script is run again.
INSERT INTO accounts (id, owner_id, balance_minor, currency) VALUES
    ('00000000-0000-0000-0000-000000000001', 'alice', 100000, 'USD'),
    ('00000000-0000-0000-0000-000000000002', 'bob', 25000, 'USD'),
    ('00000000-0000-0000-0000-000000000003', 'alice', 50000, 'USD')
ON CONFLICT (id) DO NOTHING;
