-- The PREM number NECTA keeps for every candidate (the unique number given at primary school, on later results too): taken from
-- NECTA with the Form Four (CSEE) results, kept on the results, the applicant and, after hand-over, the student.
ALTER TABLE applicant_qualifications ADD COLUMN prem_number VARCHAR(30);
ALTER TABLE applicants ADD COLUMN prem_number VARCHAR(30);
ALTER TABLE students ADD COLUMN prem_number VARCHAR(30);
