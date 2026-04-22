-- Backfill: mark any player who is a Conn Smythe prediction pick as drafted=true
-- so the live stat sync pipeline includes them going forward.
UPDATE players
SET drafted = true
WHERE id IN (
    SELECT DISTINCT conn_smythe_prediction_player_id
    FROM pool_teams
    WHERE conn_smythe_prediction_player_id IS NOT NULL
);
