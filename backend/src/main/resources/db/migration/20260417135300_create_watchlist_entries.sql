-- Migration: Create watchlist_entries table
-- Run this manually against your PostgreSQL database, or add Flyway to auto-apply it.

CREATE TABLE IF NOT EXISTS watchlist_entries (
    id         BIGSERIAL    PRIMARY KEY,
    team_id    BIGINT       NOT NULL REFERENCES pool_teams(id) ON DELETE CASCADE,
    player_id  BIGINT       NOT NULL REFERENCES players(id)    ON DELETE CASCADE,
    rank       INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uq_watchlist_team_player UNIQUE (team_id, player_id)
);

CREATE INDEX IF NOT EXISTS idx_watchlist_team_rank
    ON watchlist_entries (team_id, rank);

-- ── Undo (rollback) ──────────────────────────────────────────────────────────
-- Run this section to fully revert the migration:
--
--   DROP INDEX IF EXISTS idx_watchlist_team_rank;
--   DROP TABLE IF EXISTS watchlist_entries;
