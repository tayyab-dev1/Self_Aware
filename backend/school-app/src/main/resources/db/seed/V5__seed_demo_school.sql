-- =============================================================================
-- Demo school for the POC, as of 14 September 2026.
--
--   1 branch, 1 user, 1 open session (2026-27)
--   2 classes, 3 sections:  Class 5 Blue (12 students)
--                           Class 5 Green (10 students)
--                           Class 6 Blue  (8 students)
--   30 students, 27 guardians (two families have two children)
--   60 invoices: August and September 2026, one per student per month
--
-- Built on purpose to exercise the later phases. DatabaseMigrationIT pins these
-- numbers, so change the two together.
--
--   Paid / partly paid / unpaid invoices ........ 43 / 3 / 14
--   Class 5 Blue: 8 students owe, but only 7 guardians (twins Hassan and
--     Hussain Ali share one), 84,500 outstanding; only 5 of those 7 guardians
--     have WhatsApp (one has a mobile only, one has email only). "Confirm 7,
--     send 5" is exactly the count drift the count rule exists to prevent.
--   Class 5 Green: 4 students / 4 guardians owe, 32,000 outstanding.
--   Class 6 Blue: nobody owes anything, so "class has defaulters" fails.
--   "class 5" matches two sections, "blue" matches two sections, and
--     "ahmed" matches two students: ambiguity for the resolvers.
--   Siblings across classes: Ahmed Raza (5 Blue) and Bilal Raza (6 Blue);
--     Ayesha Khan (5 Blue) and Daniyal Khan (6 Blue).
--
-- Fee rules used below:
--   tuition 6,000 (Class 5) or 6,500 (Class 6); transport 2,500 if used;
--   an invoice not fully paid by its due date carries a 500 late fee line.
--   Payment stories per invoice:
--     paid_on_time  full amount, 5 days before the due date
--     paid_late     full amount including the late fee, 3 days after it
--     partial       half of tuition + transport, 2 days before it
--     unpaid        no payment
-- =============================================================================

INSERT INTO branches (code, name) VALUES ('LHR', 'Lahore Campus');

INSERT INTO app_users (username, full_name, role, branch_id)
SELECT 'sana.iqbal', 'Sana Iqbal', 'accounts_officer', b.id
FROM branches b WHERE b.code = 'LHR';

INSERT INTO academic_sessions (branch_id, name, starts_on, ends_on, is_open)
SELECT b.id, '2026-27', DATE '2026-08-01', DATE '2027-06-30', true
FROM branches b WHERE b.code = 'LHR';

INSERT INTO classes (session_id, name, display_order)
SELECT s.id, c.name, c.display_order
FROM academic_sessions s
CROSS JOIN (VALUES ('Class 5', 5), ('Class 6', 6)) AS c (name, display_order)
WHERE s.name = '2026-27';

INSERT INTO sections (class_id, name, capacity)
SELECT c.id, v.section_name, 30
FROM (VALUES ('Class 5', 'Blue'), ('Class 5', 'Green'), ('Class 6', 'Blue')) AS v (class_name, section_name)
JOIN classes c ON c.name = v.class_name;

-- ---------------------------------------------------------------------------
-- Families: one guardian each. Numbers are placeholders; emails use example.com.
-- ---------------------------------------------------------------------------
CREATE TEMPORARY TABLE seed_family (
    family_code      text PRIMARY KEY,
    guardian_name    text NOT NULL,
    relationship     text NOT NULL,
    mobile_number    text,
    whatsapp_number  text,
    email            text
) ON COMMIT DROP;

INSERT INTO seed_family VALUES
    ('FAM-0101', 'Raza Hussain',   'Father', '03000000101', '03000000101', NULL),
    ('FAM-0102', 'Imran Khan',     'Father', '03000000102', '03000000102', 'imran.khan@example.com'),
    ('FAM-0103', 'Farah Ali',      'Mother', '03000000103', '03000000103', NULL),
    ('FAM-0104', 'Noor Muhammad',  'Father', '03000000104', NULL,          NULL),  -- mobile only
    ('FAM-0105', 'Tariq Mehmood',  'Father', '03000000105', '03000000105', NULL),
    ('FAM-0106', 'Asma Siddiqui',  'Mother', '03000000106', '03000000106', 'asma.siddiqui@example.com'),
    ('FAM-0107', 'Kamran Sheikh',  'Father', '03000000107', '03000000107', NULL),
    ('FAM-0108', 'Javed Iqbal',    'Father', NULL,          NULL,          'javed.iqbal@example.com'),  -- email only
    ('FAM-0109', 'Hassan Raza',    'Father', '03000000109', '03000000109', NULL),
    ('FAM-0110', 'Nadia Malik',    'Mother', '03000000110', '03000000110', 'nadia.malik@example.com'),
    ('FAM-0111', 'Farooq Ahmed',   'Father', '03000000111', '03000000111', NULL),
    ('FAM-0121', 'Ali Akbar',      'Father', '03000000121', '03000000121', NULL),
    ('FAM-0122', 'Aslam Pervaiz',  'Father', '03000000122', '03000000122', NULL),
    ('FAM-0123', 'Kamal Uddin',    'Father', '03000000123', '03000000123', NULL),
    ('FAM-0124', 'Shahid Latif',   'Father', '03000000124', '03000000124', 'shahid.latif@example.com'),
    ('FAM-0125', 'Rubina Qureshi', 'Mother', '03000000125', '03000000125', NULL),
    ('FAM-0126', 'Bashir Ahmad',   'Father', '03000000126', NULL,          NULL),  -- mobile only
    ('FAM-0127', 'Waseem Butt',    'Father', '03000000127', '03000000127', NULL),
    ('FAM-0128', 'Aziz Ur Rehman', 'Father', '03000000128', '03000000128', NULL),
    ('FAM-0129', 'Yousaf Khan',    'Father', '03000000129', '03000000129', NULL),
    ('FAM-0130', 'Saima Fatima',   'Mother', '03000000130', '03000000130', 'saima.fatima@example.com'),
    ('FAM-0131', 'Anwar Saeed',    'Father', '03000000131', '03000000131', NULL),
    ('FAM-0132', 'Nadeem Akhtar',  'Father', '03000000132', '03000000132', NULL),
    ('FAM-0133', 'Rashid Mahmood', 'Father', '03000000133', '03000000133', 'rashid.mahmood@example.com'),
    ('FAM-0134', 'Ibrahim Qadir',  'Father', '03000000134', '03000000134', NULL),
    ('FAM-0135', 'Imtiaz Hussain', 'Father', '03000000135', '03000000135', NULL),
    ('FAM-0136', 'Haider Abbas',   'Father', '03000000136', '03000000136', NULL);

-- ---------------------------------------------------------------------------
-- Students: placement, transport, and what happened to each month's invoice.
-- ---------------------------------------------------------------------------
CREATE TEMPORARY TABLE seed_student (
    admission_no    text PRIMARY KEY,
    full_name       text    NOT NULL,
    gender          text    NOT NULL,
    date_of_birth   date    NOT NULL,
    family_code     text    NOT NULL REFERENCES seed_family (family_code),
    class_name      text    NOT NULL,
    section_name    text    NOT NULL,
    uses_transport  boolean NOT NULL,
    aug_story       text    NOT NULL CHECK (aug_story IN ('paid_on_time', 'paid_late', 'partial', 'unpaid')),
    sep_story       text    NOT NULL CHECK (sep_story IN ('paid_on_time', 'paid_late', 'partial', 'unpaid'))
) ON COMMIT DROP;

INSERT INTO seed_student VALUES
    -- Class 5 Blue
    ('2026-0501', 'Ahmed Raza',       'Male',   '2016-02-11', 'FAM-0101', 'Class 5', 'Blue',  true,  'paid_on_time', 'unpaid'),
    ('2026-0502', 'Ayesha Khan',      'Female', '2016-03-14', 'FAM-0102', 'Class 5', 'Blue',  false, 'paid_on_time', 'paid_on_time'),
    ('2026-0503', 'Hassan Ali',       'Male',   '2015-11-02', 'FAM-0103', 'Class 5', 'Blue',  true,  'unpaid',       'unpaid'),
    ('2026-0504', 'Hussain Ali',      'Male',   '2015-11-02', 'FAM-0103', 'Class 5', 'Blue',  true,  'unpaid',       'unpaid'),
    ('2026-0505', 'Fatima Noor',      'Female', '2016-01-20', 'FAM-0104', 'Class 5', 'Blue',  false, 'paid_late',    'unpaid'),
    ('2026-0506', 'Usman Tariq',      'Male',   '2015-10-08', 'FAM-0105', 'Class 5', 'Blue',  false, 'paid_on_time', 'partial'),
    ('2026-0507', 'Zainab Siddiqui',  'Female', '2016-05-30', 'FAM-0106', 'Class 5', 'Blue',  true,  'paid_on_time', 'paid_on_time'),
    ('2026-0508', 'Hamza Sheikh',     'Male',   '2015-12-17', 'FAM-0107', 'Class 5', 'Blue',  false, 'partial',      'unpaid'),
    ('2026-0509', 'Maryam Javed',     'Female', '2016-04-09', 'FAM-0108', 'Class 5', 'Blue',  false, 'paid_on_time', 'unpaid'),
    ('2026-0510', 'Ali Hassan',       'Male',   '2016-07-21', 'FAM-0109', 'Class 5', 'Blue',  true,  'paid_on_time', 'paid_late'),
    ('2026-0511', 'Sara Malik',       'Female', '2015-09-25', 'FAM-0110', 'Class 5', 'Blue',  false, 'paid_on_time', 'paid_on_time'),
    ('2026-0512', 'Omar Farooq',      'Male',   '2016-06-03', 'FAM-0111', 'Class 5', 'Blue',  false, 'unpaid',       'unpaid'),
    -- Class 5 Green
    ('2026-0521', 'Ahmed Ali',        'Male',   '2016-01-05', 'FAM-0121', 'Class 5', 'Green', false, 'paid_on_time', 'unpaid'),
    ('2026-0522', 'Hira Aslam',       'Female', '2015-10-19', 'FAM-0122', 'Class 5', 'Green', true,  'paid_on_time', 'paid_on_time'),
    ('2026-0523', 'Zoya Kamal',       'Female', '2016-02-27', 'FAM-0123', 'Class 5', 'Green', false, 'paid_on_time', 'paid_on_time'),
    ('2026-0524', 'Iqra Shahid',      'Female', '2016-03-03', 'FAM-0124', 'Class 5', 'Green', false, 'paid_late',    'paid_on_time'),
    ('2026-0525', 'Saad Qureshi',     'Male',   '2015-12-01', 'FAM-0125', 'Class 5', 'Green', true,  'paid_on_time', 'unpaid'),
    ('2026-0526', 'Amna Bashir',      'Female', '2016-08-15', 'FAM-0126', 'Class 5', 'Green', false, 'paid_on_time', 'paid_on_time'),
    ('2026-0527', 'Rayan Butt',       'Male',   '2016-05-11', 'FAM-0127', 'Class 5', 'Green', false, 'unpaid',       'unpaid'),
    ('2026-0528', 'Mahnoor Aziz',     'Female', '2015-09-09', 'FAM-0128', 'Class 5', 'Green', false, 'paid_on_time', 'partial'),
    ('2026-0529', 'Talha Yousaf',     'Male',   '2016-06-24', 'FAM-0129', 'Class 5', 'Green', true,  'paid_on_time', 'paid_on_time'),
    ('2026-0530', 'Eman Fatima',      'Female', '2016-04-18', 'FAM-0130', 'Class 5', 'Green', false, 'paid_on_time', 'paid_on_time'),
    -- Class 6 Blue
    ('2026-0601', 'Bilal Raza',       'Male',   '2014-12-06', 'FAM-0101', 'Class 6', 'Blue',  true,  'paid_on_time', 'paid_on_time'),
    ('2026-0602', 'Daniyal Khan',     'Male',   '2014-10-12', 'FAM-0102', 'Class 6', 'Blue',  false, 'paid_on_time', 'paid_on_time'),
    ('2026-0603', 'Khadija Anwar',    'Female', '2015-01-22', 'FAM-0131', 'Class 6', 'Blue',  false, 'paid_on_time', 'paid_on_time'),
    ('2026-0604', 'Abdullah Nadeem',  'Male',   '2014-11-30', 'FAM-0132', 'Class 6', 'Blue',  false, 'paid_on_time', 'paid_late'),
    ('2026-0605', 'Laiba Rashid',     'Female', '2015-03-17', 'FAM-0133', 'Class 6', 'Blue',  false, 'paid_late',    'paid_on_time'),
    ('2026-0606', 'Muhammad Ibrahim', 'Male',   '2015-05-05', 'FAM-0134', 'Class 6', 'Blue',  false, 'paid_on_time', 'paid_on_time'),
    ('2026-0607', 'Anaya Imtiaz',     'Female', '2015-07-28', 'FAM-0135', 'Class 6', 'Blue',  false, 'paid_on_time', 'paid_on_time'),
    ('2026-0608', 'Zayan Haider',     'Male',   '2014-09-14', 'FAM-0136', 'Class 6', 'Blue',  false, 'paid_on_time', 'paid_on_time');

INSERT INTO guardians (branch_id, family_code, full_name, relationship, mobile_number, whatsapp_number, email)
SELECT b.id, f.family_code, f.guardian_name, f.relationship, f.mobile_number, f.whatsapp_number, f.email
FROM seed_family f
CROSS JOIN branches b
WHERE b.code = 'LHR'
ORDER BY f.family_code;

INSERT INTO students (branch_id, admission_no, full_name, gender, date_of_birth, guardian_id)
SELECT g.branch_id, s.admission_no, s.full_name, s.gender, s.date_of_birth, g.id
FROM seed_student s
JOIN guardians g ON g.family_code = s.family_code
ORDER BY s.admission_no;

INSERT INTO enrolments (student_id, section_id, session_id, enrolled_on)
SELECT st.id, sec.id, cls.session_id, DATE '2026-08-01'
FROM seed_student s
JOIN students st  ON st.admission_no = s.admission_no
JOIN classes cls  ON cls.name = s.class_name
JOIN sections sec ON sec.class_id = cls.id AND sec.name = s.section_name
ORDER BY s.admission_no;

-- ---------------------------------------------------------------------------
-- Invoices: one per student per billing month, with its payment story.
-- ---------------------------------------------------------------------------
CREATE TEMPORARY TABLE seed_invoice ON COMMIT DROP AS
SELECT 'INV/LHR/26-27/' || lpad(row_number() OVER (ORDER BY p.billing_period, s.admission_no)::text, 6, '0') AS invoice_no,
       s.admission_no,
       p.billing_period,
       p.issued_on,
       p.due_on,
       CASE p.month WHEN 'aug' THEN s.aug_story ELSE s.sep_story END             AS story,
       CASE s.class_name WHEN 'Class 5' THEN 6000.00 ELSE 6500.00 END            AS tuition,
       CASE WHEN s.uses_transport THEN 2500.00 ELSE 0.00 END                     AS transport,
       row_number() OVER (ORDER BY p.billing_period, s.admission_no)             AS seq
FROM seed_student s
CROSS JOIN (VALUES ('aug', DATE '2026-08-01', DATE '2026-08-01', DATE '2026-08-10'),
                   ('sep', DATE '2026-09-01', DATE '2026-09-01', DATE '2026-09-10'))
           AS p (month, billing_period, issued_on, due_on);

INSERT INTO fee_invoices (branch_id, session_id, student_id, section_id, invoice_no, billing_period, issued_on, due_on)
SELECT st.branch_id, e.session_id, st.id, e.section_id, si.invoice_no, si.billing_period, si.issued_on, si.due_on
FROM seed_invoice si
JOIN students st  ON st.admission_no = si.admission_no
JOIN enrolments e ON e.student_id = st.id
ORDER BY si.seq;

INSERT INTO fee_invoice_lines (invoice_id, line_type, description, amount)
SELECT fi.id, line.line_type, line.description, line.amount
FROM seed_invoice si
JOIN fee_invoices fi ON fi.invoice_no = si.invoice_no
CROSS JOIN LATERAL (
    SELECT 'Tuition' AS line_type, 'Tuition fee, ' || to_char(si.billing_period, 'FMMonth YYYY') AS description, si.tuition AS amount
    UNION ALL
    SELECT 'Transport', 'School transport, ' || to_char(si.billing_period, 'FMMonth YYYY'), si.transport
    WHERE si.transport > 0
    UNION ALL
    SELECT 'LateFee', 'Late fee: not paid in full by ' || to_char(si.due_on, 'FMDD FMMonth YYYY'), 500.00
    WHERE si.story IN ('unpaid', 'partial', 'paid_late')
) line
ORDER BY si.seq, line.line_type DESC;

INSERT INTO fee_payments (invoice_id, receipt_no, amount, method, paid_on, recorded_by)
SELECT fi.id,
       'RCT/LHR/26-27/' || lpad(row_number() OVER (ORDER BY pay.paid_on, si.seq)::text, 6, '0'),
       pay.amount,
       (ARRAY['Cash', 'BankChallan', 'OnlineGateway'])[1 + (si.seq % 3)],
       pay.paid_on,
       u.id
FROM seed_invoice si
JOIN fee_invoices fi ON fi.invoice_no = si.invoice_no
CROSS JOIN LATERAL (
    SELECT CASE si.story
               WHEN 'paid_on_time' THEN si.tuition + si.transport
               WHEN 'paid_late'    THEN si.tuition + si.transport + 500.00
               WHEN 'partial'      THEN (si.tuition + si.transport) / 2
           END AS amount,
           CASE si.story
               WHEN 'paid_on_time' THEN si.due_on - 5
               WHEN 'paid_late'    THEN si.due_on + 3
               WHEN 'partial'      THEN si.due_on - 2
           END AS paid_on
) pay
CROSS JOIN app_users u
WHERE si.story <> 'unpaid'
  AND u.username = 'sana.iqbal'
ORDER BY pay.paid_on, si.seq;
