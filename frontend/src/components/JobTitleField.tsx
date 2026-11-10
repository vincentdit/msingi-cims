import { Autocomplete, TextField } from '@mui/material';
import { useMemo } from 'react';

/** Titles commonly used in Tanzanian colleges and technical institutions (NACTVET), by area. */
export const JOB_TITLES: Record<string, string[]> = {
  'Leadership': [
    'Principal', 'Deputy Principal (Academics, Research and Consultancy)', 'Deputy Principal (Planning, Finance and Administration)',
    'Director', 'Assistant Director', 'Board Secretary', 'Personal Assistant to the Principal',
  ],
  'Academic': [
    'Dean of Faculty', 'Head of Department', 'Deputy Head of Department', 'Programme Coordinator', 'Principal Lecturer', 'Senior Lecturer',
    'Lecturer', 'Assistant Lecturer', 'Tutorial Assistant', 'Tutor', 'Clinical Instructor', 'Instructor', 'Assessor', 'Laboratory Scientist',
    'Laboratory Technician', 'Workshop Technician', 'Research and Consultancy Coordinator', 'Short Courses Coordinator', 'IPT Coordinator',
    'Quality Assurance Officer',
  ],
  'Registry, admissions and examinations': [
    'Registrar', 'Deputy Registrar', 'Admissions Officer', 'Registry Officer', 'Records Officer', 'Examinations Officer', 'Examinations Assistant',
  ],
  'Library': ['Librarian', 'Assistant Librarian', 'Library Assistant'],
  'Finance and procurement': [
    'Bursar', 'Chief Accountant', 'Accountant', 'Assistant Accountant', 'Accounts Assistant', 'Cashier', 'Revenue Officer', 'Loans Officer',
    'Internal Auditor', 'Procurement Officer', 'Supplies Officer', 'Storekeeper',
  ],
  'Administration and support': [
    'Human Resources Officer', 'Administrative Officer', 'Planning Officer', 'Legal Officer', 'Public Relations Officer', 'ICT Officer',
    'Systems Administrator', 'Office Management Secretary', 'Personal Secretary', 'Estates Officer', 'Transport Officer', 'Driver',
    'Security Officer',
  ],
  'Student services': [
    'Dean of Students', 'Hostel Warden', 'Matron', 'Patron', 'Counsellor', 'Games and Sports Officer', 'Clinical Officer', 'Nurse', 'Catering Officer',
  ],
};

/** The titles that usually go with each role, offered first once the roles are ticked. */
const BY_ROLE: Record<string, string[]> = {
  INSTITUTION_ADMIN: ['Principal', 'ICT Officer', 'Systems Administrator'],
  MANAGEMENT_VIEWER: ['Principal', 'Deputy Principal (Planning, Finance and Administration)', 'Director'],
  DEPUTY_PRINCIPAL_ACADEMIC: ['Deputy Principal (Academics, Research and Consultancy)'],
  ADMISSIONS_OFFICER: ['Admissions Officer'],
  REGISTRAR: ['Registrar', 'Deputy Registrar', 'Registry Officer'],
  HEAD_OF_DEPARTMENT: ['Head of Department', 'Dean of Faculty'],
  PROGRAMME_COORDINATOR: ['Programme Coordinator'],
  LECTURER: ['Lecturer', 'Assistant Lecturer', 'Tutor', 'Clinical Instructor'],
  ASSESSOR: ['Assessor', 'Instructor'],
  EXAMINATIONS_OFFICER: ['Examinations Officer'],
  BURSAR: ['Bursar', 'Chief Accountant'],
  ACCOUNTANT: ['Accountant', 'Assistant Accountant', 'Cashier'],
  HOSTEL_WARDEN: ['Hostel Warden', 'Matron', 'Patron'],
  IPT_COORDINATOR: ['IPT Coordinator'],
  CLEARANCE_OFFICER: ['Registry Officer', 'Librarian', 'Storekeeper'],
  SPONSOR_OFFICER: ['Loans Officer'],
  AUDITOR: ['Internal Auditor'],
  LIBRARIAN: ['Librarian', 'Assistant Librarian', 'Library Assistant'],
  HR_OFFICER: ['Human Resources Officer', 'Human Resources Manager', 'Administrative Officer'],
  STORES_OFFICER: ['Supplies Officer', 'Storekeeper', 'Estates Officer'],
  DEAN_OF_STUDENTS: ['Dean of Students', 'Students Welfare Officer', 'Counsellor'],
  QUALITY_ASSURANCE_OFFICER: ['Quality Assurance Officer', 'Quality Assurance Coordinator'],
  PROCUREMENT_OFFICER: ['Procurement Officer', 'Head of Procurement Management Unit', 'Supplies Officer'],
};

type Option = { title: string; group: string };

/**
 * The job title of a staff account: chosen from the titles colleges commonly use (those that fit the ticked roles first, then the
 * titles already used at this institution), or typed when it is none of them.
 */
export function JobTitleField({ value, onChange, roles = [], used = [], disabled }: {
  value: string; onChange: (v: string) => void; roles?: string[]; used?: string[]; disabled?: boolean;
}) {
  const options = useMemo<Option[]>(() => {
    const seen = new Set<string>();
    const out: Option[] = [];
    const add = (title: string, group: string) => {
      const t = title.trim();
      if (!t || seen.has(t.toLowerCase())) return;
      seen.add(t.toLowerCase());
      out.push({ title: t, group });
    };
    roles.flatMap((r) => BY_ROLE[r] ?? []).forEach((t) => add(t, 'Suggested for the roles chosen'));
    Object.entries(JOB_TITLES).forEach(([g, list]) => list.forEach((t) => add(t, g)));
    [...used].sort((a, b) => a.localeCompare(b)).forEach((t) => add(t, 'Also used at this institution'));
    return out;
  }, [roles.join(), used.join()]);

  return (
    <Autocomplete freeSolo disabled={disabled} options={options} groupBy={(o) => o.group} value={value}
      getOptionLabel={(o) => (typeof o === 'string' ? o : o.title)}
      isOptionEqualToValue={(o, v) => (typeof v === 'string' ? o.title === v : o.title === v.title)}
      onChange={(_, v) => onChange(typeof v === 'string' ? v : v?.title ?? '')}
      onInputChange={(_, v, reason) => { if (reason === 'input' || reason === 'clear') onChange(v); }}
      slotProps={{ listbox: { sx: { maxHeight: 360 } } }}
      renderInput={(p) => <TextField {...p} size="small" label="Job title" placeholder="Choose or type" />} />
  );
}
