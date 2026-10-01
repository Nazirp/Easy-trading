-- Easy Trading — the instrument list.
--
-- Search reads the database only and nothing in the application adds
-- instruments, so this file is what makes them exist. Candles are not seeded:
-- every chart is fetched from Twelve Data on first view and cached, so nothing
-- on screen is generated data.
--
-- Applied after schema.sql (docker-compose.yml does this on a fresh volume):
--   psql -d easytrading -f db/schema.sql
--   psql -d easytrading -f db/seed.sql

INSERT INTO instrument (symbol, name, exchange, type) VALUES
  ('EUR/USD', 'Euro / US Dollar', NULL, 'forex'),
  ('GBP/USD', 'British Pound / US Dollar', NULL, 'forex'),
  ('BTC/USD', 'Bitcoin / US Dollar', NULL, 'crypto'),
  ('ETH/USD', 'Ethereum / US Dollar', NULL, 'crypto'),
  ('AAPL', 'Apple Inc.', 'NASDAQ', 'stock'),
  ('MSFT', 'Microsoft Corporation', 'NASDAQ', 'stock')
ON CONFLICT (symbol) DO NOTHING;
