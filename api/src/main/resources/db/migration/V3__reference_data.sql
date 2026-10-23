-- V3: the catalogue of the fictional Fynbos Bank: six branches, their trading hours, six services and the capacity of
-- each service at each branch.
--
-- Every name and street address is made up. The postal codes and provinces are real, so the addresses read
-- naturally. The ids are fixed so tests and scripts can refer to them; no migration ever contains personal data.
--
-- Branches chosen for the demos and tests:
-- - POL (Polokwane Savannah) has capacity 1 for every service, so two people racing for one seat is easy to show.
-- - SAN (Sandton Summit Mall) and UMH (Umhlanga Ridge) are the only branches open on Sundays.
-- - RUS (Rustenburg Bojanala) closes an hour early on Saturdays, so slot tests cover a shorter day.
-- - GQE (Gqeberha Baywater) does not offer home loans, so tests cover a service a branch does not offer.

INSERT INTO branches (id, code, name, sms_name, address_line1, suburb, city, province, postal_code) VALUES
    ('10000000-0000-4000-8000-000000000001', 'SAN', 'Sandton Summit Mall', 'Sandton Summit',
        'Shop 4, 1 Protea Lane', 'Sandton', 'Johannesburg', 'Gauteng', '2196'),
    ('10000000-0000-4000-8000-000000000002', 'BEL', 'Bellville Kingsway', 'Bellville Kingsway',
        '12 Fynbos Street', 'Bellville', 'Cape Town', 'Western Cape', '7530'),
    ('10000000-0000-4000-8000-000000000003', 'UMH', 'Umhlanga Ridge', 'Umhlanga Ridge',
        '3 Strelitzia Walk', 'Umhlanga', 'Durban', 'KwaZulu-Natal', '4319'),
    ('10000000-0000-4000-8000-000000000004', 'GQE', 'Gqeberha Baywater', 'Gqeberha Baywater',
        '8 Aloe Crescent', 'Mill Park', 'Gqeberha', 'Eastern Cape', '6001'),
    ('10000000-0000-4000-8000-000000000005', 'POL', 'Polokwane Savannah', 'Polokwane Savannah',
        '5 Marula Road', 'Polokwane Central', 'Polokwane', 'Limpopo', '0699'),
    ('10000000-0000-4000-8000-000000000006', 'RUS', 'Rustenburg Bojanala', 'Rustenburg Bojanala',
        '9 Baobab Avenue', 'Rustenburg Central', 'Rustenburg', 'North West', '0299');

-- Monday to Friday, 08:00 to 17:00, at every branch.
INSERT INTO branch_operating_hours (branch_id, weekday, opens_at, closes_at)
SELECT b.id, d.weekday, '08:00', '17:00'
FROM branches b CROSS JOIN generate_series(1, 5) AS d(weekday);

-- Saturday, 08:00 to 13:00, except Rustenburg, which closes at 12:00.
INSERT INTO branch_operating_hours (branch_id, weekday, opens_at, closes_at)
SELECT b.id, 6, '08:00', CASE b.code WHEN 'RUS' THEN '12:00' ELSE '13:00' END::time
FROM branches b;

-- Sunday, 09:00 to 13:00, at the two mall branches only. Every other branch is closed on Sundays.
INSERT INTO branch_operating_hours (branch_id, weekday, opens_at, closes_at)
SELECT b.id, 7, '09:00', '13:00'
FROM branches b WHERE b.code IN ('SAN', 'UMH');

INSERT INTO services (id, code, name, sms_name, description, duration_minutes, documents_needed, sort_order) VALUES
    ('20000000-0000-4000-8000-000000000001', 'FICA', 'Update personal details (FICA)', 'FICA update',
        'Update your address, contact details or identity document, as the Financial Intelligence Centre Act requires.',
        15, ARRAY['Valid SA ID or passport with permit', 'Supporting document for the change, if any'], 1),
    ('20000000-0000-4000-8000-000000000002', 'CARD', 'Card replacement or collection', 'Card replacement',
        'Replace a lost, stolen or damaged card, or collect a card you ordered.',
        15, ARRAY['Valid SA ID or passport', 'Your old card if you still have it'], 2),
    ('20000000-0000-4000-8000-000000000003', 'OPEN', 'Open a personal account', 'Account opening',
        'Open a transactional or savings account with a consultant.',
        30, ARRAY['SA Smart ID or green ID book, or passport with a valid visa or permit',
            'Cellphone registered in your name'], 3),
    ('20000000-0000-4000-8000-000000000004', 'CREDIT', 'Credit or personal loan consultation', 'Credit consult',
        'Talk through a personal loan or credit facility, and what you can afford.',
        30, ARRAY['Valid SA ID', 'Latest payslip',
            'Latest 3 months of bank statements if your salary is not paid into Fynbos Bank'], 4),
    ('20000000-0000-4000-8000-000000000005', 'ESTATE', 'Deceased estate assistance', 'Deceased estate',
        'Get help with the accounts of someone who has passed away.',
        30, ARRAY['Certified copy of the death certificate', 'Certified copy of the deceased''s ID', 'Your own ID',
            'Letter of executorship, if issued'], 5),
    ('20000000-0000-4000-8000-000000000006', 'HOME', 'Home loan consultation', 'Home loan consult',
        'Talk through a home loan application with a consultant.',
        45, ARRAY['Valid SA ID', 'Latest 3 months of payslips', 'Latest 3 months of bank statements',
            'Offer to purchase, if you have one'], 6);

-- Capacity: Polokwane has one seat per service; Sandton is the busiest; Gqeberha offers no home loans; every other
-- branch has two seats per service.
INSERT INTO branch_services (branch_id, service_id, capacity)
SELECT b.id, s.id,
    CASE
        WHEN b.code = 'POL' THEN 1
        WHEN b.code = 'SAN' AND s.code IN ('FICA', 'CREDIT') THEN 3
        ELSE 2
    END
FROM branches b CROSS JOIN services s
WHERE NOT (b.code = 'GQE' AND s.code = 'HOME');
