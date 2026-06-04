-- Idempotent zhparser setup for Chinese full-text search.
-- This script runs automatically when the PostgreSQL container initializes
-- (mounted as a docker-entrypoint-initdb.d script).

CREATE EXTENSION IF NOT EXISTS zhparser;

-- Create a text search configuration using zhparser for Chinese tokenization.
-- zhparser handles: Chinese word segmentation, stop words, and mixed CJK/Latin text.
CREATE TEXT SEARCH CONFIGURATION chinese (PARSER = zhparser);

-- Map token types to dictionaries:
--   n = noun, v = verb, a = adjective, i = idiom, e = exclamation, l = temporary
-- Use 'simple' dictionary (no stemming, just lowercasing) for Chinese tokens.
ALTER TEXT SEARCH CONFIGURATION chinese
    ADD MAPPING FOR n,v,a,i,e,l WITH simple;
