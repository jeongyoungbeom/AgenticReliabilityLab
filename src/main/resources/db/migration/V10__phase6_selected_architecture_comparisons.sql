-- Phase 6 records the exact user-selected single/multi architecture and model
-- combinations. Existing Phase 3/4 comparisons remain readable as implicit
-- SINGLE selections derived from model_keys_json.
alter table analysis_comparison add column configuration_json text;
alter table analysis_comparison add column configuration_hash varchar(128);
