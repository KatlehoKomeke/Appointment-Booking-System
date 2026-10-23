-- V1: enables the btree_gist extension.
--
-- Double booking is prevented by EXCLUDE USING gist constraints (docs/adr/0002-double-booking-prevention.md). Each
-- one combines equality on plain columns (the branch service and seat, or the customer's mobile number) with
-- overlap on a time range. A GiST index cannot compare plain columns for equality by itself; btree_gist adds those
-- operator classes. It ships with PostgreSQL, and it is a trusted extension that Amazon RDS also allows.
CREATE EXTENSION IF NOT EXISTS btree_gist;
