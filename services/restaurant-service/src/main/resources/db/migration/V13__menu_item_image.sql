-- restaurant-service V13 — menu item image URL.
--
-- Stores an optional image per menu item — either a small base64 data URL
-- (the frontend downsizes an uploaded photo before sending) or an external
-- HTTP(S) URL. TEXT allows both forms with no length restriction at the DB
-- layer; the application enforces a 3 MB soft cap via @Size validation.
--
-- Additive migration only; RLS policy on menu_item already exists (V2).

ALTER TABLE menu_item
    ADD COLUMN IF NOT EXISTS image_url TEXT NULL;
