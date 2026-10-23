-- V4: South African public holidays for 2026 and 2027, so branches close on them (BR-3).
--
-- Only the dates in law are stored:
-- - SCHEDULE_1: the holidays in Schedule 1 of the Public Holidays Act 36 of 1994. Good Friday and Family Day follow
--   Easter (5 April 2026, 28 March 2027).
-- - PROCLAMATION: days the President declares under section 2A of the Act.
--
-- When a holiday falls on a Sunday, section 2(1) makes the following Monday a public holiday. Those Mondays are not
-- stored; PublicHolidayCalendar derives them: 10 August 2026, 22 March 2027 and 27 December 2027. A holiday on a
-- Saturday gets no substitute (21 March 2026, 26 December 2026, 1 May 2027, 25 December 2027).
--
-- Coverage ends on 31 December 2027. The 2028 holidays are a new migration, not a code change.

INSERT INTO public_holidays (holiday_date, name, source) VALUES
    ('2026-01-01', 'New Year''s Day', 'SCHEDULE_1'),
    ('2026-03-21', 'Human Rights Day', 'SCHEDULE_1'),
    ('2026-04-03', 'Good Friday', 'SCHEDULE_1'),
    ('2026-04-06', 'Family Day', 'SCHEDULE_1'),
    ('2026-04-27', 'Freedom Day', 'SCHEDULE_1'),
    ('2026-05-01', 'Workers'' Day', 'SCHEDULE_1'),
    ('2026-06-16', 'Youth Day', 'SCHEDULE_1'),
    ('2026-08-09', 'National Women''s Day', 'SCHEDULE_1'),
    ('2026-09-24', 'Heritage Day', 'SCHEDULE_1'),
    ('2026-12-16', 'Day of Reconciliation', 'SCHEDULE_1'),
    ('2026-12-25', 'Christmas Day', 'SCHEDULE_1'),
    ('2026-12-26', 'Day of Goodwill', 'SCHEDULE_1'),
    ('2027-01-01', 'New Year''s Day', 'SCHEDULE_1'),
    ('2027-03-21', 'Human Rights Day', 'SCHEDULE_1'),
    ('2027-03-26', 'Good Friday', 'SCHEDULE_1'),
    ('2027-03-29', 'Family Day', 'SCHEDULE_1'),
    ('2027-04-27', 'Freedom Day', 'SCHEDULE_1'),
    ('2027-05-01', 'Workers'' Day', 'SCHEDULE_1'),
    ('2027-06-16', 'Youth Day', 'SCHEDULE_1'),
    ('2027-08-09', 'National Women''s Day', 'SCHEDULE_1'),
    ('2027-09-24', 'Heritage Day', 'SCHEDULE_1'),
    ('2027-12-16', 'Day of Reconciliation', 'SCHEDULE_1'),
    ('2027-12-25', 'Christmas Day', 'SCHEDULE_1'),
    ('2027-12-26', 'Day of Goodwill', 'SCHEDULE_1');

-- Declared by the President under section 2A for the local government elections, announced by the Presidency on
-- 15 September 2026:
-- https://thepresidency.gov.za/president-ramaphosa-declares-election-day-4-november-2026-public-holiday
INSERT INTO public_holidays (holiday_date, name, source) VALUES
    ('2026-11-04', 'Local Government Elections', 'PROCLAMATION');
