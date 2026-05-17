-- ============================================================
-- Enable Row Level Security on all public tables
-- ============================================================
-- Context: This app uses JWT auth managed by Spring Boot, NOT
-- Supabase Auth. All DB access goes through the backend DB user
-- (nhlpool) which is a superuser — superusers bypass RLS
-- automatically, so no policies are needed. Enabling RLS here
-- simply closes raw Supabase REST API exposure and clears the
-- Supabase security advisor CRITICAL warnings.
-- ============================================================
-- NOTE: Already applied manually to Supabase on 2026-05-17.
--       This file is kept for documentation / reproducibility.
-- ============================================================

ALTER TABLE public.users                    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.pool_teams               ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.pool_rounds              ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.players                  ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.series                   ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.series_game              ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.predictions              ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.prediction_scoring_rules ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.scoring_rules            ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.draft_picks              ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.draft_config             ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.watchlist_entries        ENABLE ROW LEVEL SECURITY;
