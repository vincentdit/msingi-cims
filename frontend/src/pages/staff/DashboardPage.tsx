import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import WarningIcon from '@mui/icons-material/WarningAmber';
import { Alert, Box, Button, Card, CardContent, Container, Stack, Tab, Tabs, Typography } from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { OverviewSection } from './OverviewSection';
import { Navigate, Link as RouterLink } from 'react-router-dom';
import { api, formatDate, formatTzs } from '../../api/client';
import type { DashboardData } from '../../api/types';
import { useSession } from '../../auth/session';
import { BarList, ColumnChart, SplitBar } from '../../components/charts';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';

const staff = { realm: 'staff' as const };
const short = (n: number) => (n >= 1_000_000 ? `${(n / 1_000_000).toFixed(n >= 10_000_000 ? 0 : 1)}M` : n >= 1000 ? `${Math.round(n / 1000)}k` : String(n));

/** Role-based dashboard (FR-RPT-01): admissions, registry and finance sections as the user's roles allow. */
export default function DashboardPage() {
  const { user } = useSession();
  const roles = user('staff')?.roles ?? [];
  // Lecturers and assessors have no dashboard: they start from their own modules.
  const teachingOnly = roles.length > 0 && roles.every((r) => r === 'LECTURER' || r === 'ASSESSOR');
  const officerOnly = roles.length > 0 && roles.every((r) => r === 'CLEARANCE_OFFICER' || r === 'HOSTEL_WARDEN' || r === 'IPT_COORDINATOR');
  const financeOnly = roles.length > 0 && roles.every((r) => r === 'BURSAR' || r === 'ACCOUNTANT');
  const q = useQuery({ queryKey: ['staff', 'dashboard'], enabled: !teachingOnly && !officerOnly && !financeOnly, queryFn: () => api<DashboardData>('/api/dashboard', staff), refetchInterval: 60_000 });
  const d = q.data;
  // the Principal, administrator and Deputy Principal (Academic) also have the institution overview; management starts there
  const overview = roles.some((r) => r === 'MANAGEMENT_VIEWER' || r === 'INSTITUTION_ADMIN' || r === 'DEPUTY_PRINCIPAL_ACADEMIC');
  const [tab, setTab] = useState<'overview' | 'today'>(roles.includes('MANAGEMENT_VIEWER') ? 'overview' : 'today');
  const approver = roles.some((r) => ['INSTITUTION_ADMIN', 'DEPUTY_PRINCIPAL_ACADEMIC', 'HEAD_OF_DEPARTMENT', 'EXAMINATIONS_OFFICER', 'BURSAR', 'MANAGEMENT_VIEWER'].includes(r));
  const waiting = useQuery({ queryKey: ['staff', 'approvals', 'count'], enabled: approver, refetchInterval: 60_000,
    queryFn: () => api<{ actionable: number }>('/api/approvals/count', staff) }).data?.actionable ?? 0;
  const first = user('staff')?.fullName?.split(' ').find((w) => !/^(mr|mrs|ms|miss|dr|prof|eng|rev)\.?$/i.test(w));
  if (teachingOnly) return <Navigate to="/staff/my-teaching" replace />;
  if (roles.length > 0 && roles.every((r) => r === 'BURSAR' || r === 'ACCOUNTANT')) return <Navigate to="/staff/finance-overview" replace />;
  if (officerOnly) return <Navigate to={roles.includes('IPT_COORDINATOR') ? '/staff/ipt' : roles.includes('HOSTEL_WARDEN') ? '/staff/hostels' : '/staff/clearance'} replace />;
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title={first ? `Welcome, ${first}` : 'Dashboard'} subtitle="Today at a glance. Figures update every minute; full detail is under Reports."
        action={<Button component={RouterLink} to="/staff/reports" endIcon={<ArrowForwardIcon />}>Reports</Button>} />
      {overview && (
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 3, borderBottom: 1, borderColor: 'divider' }}>
          <Tab value="overview" label="Institution overview" />
          <Tab value="today" label="Today's work" />
        </Tabs>
      )}
      {overview && tab === 'overview' ? <OverviewSection /> : (
        <>
          {waiting > 0 && (
            <Alert severity="warning" sx={{ mb: 3 }} action={<Button color="inherit" component={RouterLink} to="/staff/approvals" endIcon={<ArrowForwardIcon />}>Approvals</Button>}>
              {waiting === 1 ? '1 decision waits for you.' : `${waiting} decisions wait for you.`}
            </Alert>
          )}
          <ErrorAlert error={q.error} />
          {q.isLoading && <Loading />}
          {d && !d.admissions && !d.registry && !d.finance && <Alert severity="info">Your role has no dashboard figures. Use the menu to reach your work.</Alert>}
          <Stack spacing={4}>
            {d?.admissions && <AdmissionsSection a={d.admissions} />}
            {d?.registry && <RegistrySection r={d.registry} />}
            {d?.finance && <FinanceSection f={d.finance} />}
          </Stack>
        </>
      )}
    </Container>
  );
}

function Section({ title, subtitle, link, children }: { title: string; subtitle?: ReactNode; link: string; children: ReactNode }) {
  return (
    <Box component="section">
      <Stack direction="row" alignItems="baseline" spacing={1.5} sx={{ mb: 1.5 }}>
        <Typography variant="h5" component="h2">{title}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ flexGrow: 1 }}>{subtitle}</Typography>
        <Button size="small" component={RouterLink} to={link}>Reports</Button>
      </Stack>
      {children}
    </Box>
  );
}

function Kpi({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: ReactNode; tone?: string }) {
  return (
    <Grid size={{ xs: 6, md: 3 }}>
      <Card sx={{ height: '100%' }}><CardContent sx={{ py: 1.75, '&:last-child': { pb: 1.75 } }}>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
        <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: { xs: 19, md: 26 }, lineHeight: 1.25, color: tone }}>{value}</Typography>
        {hint && <Typography variant="caption" color="text.secondary">{hint}</Typography>}
      </CardContent></Card>
    </Grid>
  );
}

function Panel({ title, children, md = 6 }: { title: string; children: ReactNode; md?: number }) {
  return (
    <Grid size={{ xs: 12, md }}>
      <Card sx={{ height: '100%' }}><CardContent>
        <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 1.5 }}>{title}</Typography>
        {children}
      </CardContent></Card>
    </Grid>
  );
}

function AdmissionsSection({ a }: { a: NonNullable<DashboardData['admissions']> }) {
  if (!a.windowId) return <Section title="Admissions" link="/staff/reports?area=Admissions"><Alert severity="info">No admission window yet.</Alert></Section>;
  const val = (k: string) => a.funnel.find((x) => x.key === k)?.value ?? 0;
  const submitted = val('submitted'), accepted = val('accepted');
  return (
    <Section title="Admissions" subtitle={`${a.windowName} · ${a.windowStatus === 'PUBLISHED' ? `closes ${formatDate(a.closesAt)}` : a.windowStatus?.toLowerCase()}`}
      link="/staff/reports?area=Admissions">
      <Grid container spacing={1.5} sx={{ mb: 1.5 }}>
        <Kpi label="Applications submitted" value={submitted.toLocaleString()} hint={`${val('started') - submitted} started, fee not yet paid`} />
        <Kpi label="Selected" value={val('selected').toLocaleString()} />
        <Kpi label="Offers made" value={val('offered').toLocaleString()} />
        <Kpi label="Accepted" value={accepted.toLocaleString()} hint={val('offered') ? `${Math.round((100 * accepted) / val('offered'))}% of offers` : undefined} />
      </Grid>
      <Grid container spacing={1.5}>
        <Panel title="From application to Registry">
          <BarList rows={a.funnel.map((p) => ({ key: p.key, label: p.label, value: p.value }))} />
        </Panel>
        <Panel title="Applications submitted, last 14 days">
          <ColumnChart ariaLabel="Applications submitted per day over the last 14 days" points={a.dailySubmissions.map((p) => ({ ...p, tooltip: formatDate(p.key) }))} />
          <Box sx={{ mt: 2 }}><SplitBar parts={[{ label: 'Female', value: a.female }, { label: 'Male', value: a.male }]} /></Box>
        </Panel>
        <Panel title="Most chosen programmes (applicants with it as 1st choice)" md={12}>
          <BarList emptyText="No programmes in this window."
            rows={a.programmes.map((p) => ({ key: p.code, label: `${p.code} · ${p.name}`, value: p.value,
              display: `${p.value.toLocaleString()} for ${p.capacity.toLocaleString()} places${p.capacity && p.value > p.capacity ? ` · ${(p.value / p.capacity).toFixed(1)}× oversubscribed` : ''}`,
              note: `${p.accepted} accepted` }))} />
        </Panel>
      </Grid>
    </Section>
  );
}

function RegistrySection({ r }: { r: NonNullable<DashboardData['registry']> }) {
  return (
    <Section title="Registry" subtitle="Current students: admitted or registered" link="/staff/reports?area=Registry">
      <Grid container spacing={1.5} sx={{ mb: 1.5 }}>
        <Kpi label="Current students" value={r.current.toLocaleString()} />
        <Kpi label="Registered" value={r.registered.toLocaleString()} />
        <Kpi label="Still to report" value={r.awaitingRegistration.toLocaleString()} tone={r.awaitingRegistration ? 'warning.main' : undefined} />
        <Kpi label="Detail updates waiting" value={r.pendingChangeRequests.toLocaleString()}
          hint={r.pendingChangeRequests ? <RouterLink to="/staff/students">Review on Students</RouterLink> : 'None waiting'} />
      </Grid>
      <Grid container spacing={1.5}>
        <Panel title="Current students by programme">
          <BarList rows={r.byProgramme.map((p) => ({ key: p.code, label: `${p.code} · ${p.name}`, value: p.value, note: `${p.accepted} registered` }))}
            emptyText="No students yet." />
        </Panel>
        <Panel title="By status and sex">
          <BarList rows={r.byStatus.map((p) => ({ key: p.key, label: p.label, value: p.value }))} emptyText="No students yet." />
          <Box sx={{ mt: 2 }}><SplitBar parts={[{ label: 'Female', value: r.female }, { label: 'Male', value: r.male }]} /></Box>
        </Panel>
      </Grid>
    </Section>
  );
}

function FinanceSection({ f }: { f: NonNullable<DashboardData['finance']> }) {
  return (
    <Section title="Finance" subtitle={f.academicYear ? `Annual fees ${f.academicYear}` : undefined} link="/staff/reports?area=Finance">
      {(f.daysNotReconciled > 0 || f.openExceptions > 0) && (
        <Alert severity="warning" icon={<WarningIcon />} sx={{ mb: 1.5 }}
          action={<Button color="inherit" size="small" component={RouterLink} to="/staff/finance">Open Fees</Button>}>
          {f.daysNotReconciled > 0 && `${f.daysNotReconciled} day(s) with payments in the last 30 days are not reconciled and closed. `}
          {f.openExceptions > 0 && `${f.openExceptions} reconciliation exception(s) open.`}
        </Alert>
      )}
      <Grid container spacing={1.5} sx={{ mb: 1.5 }}>
        <Kpi label="Billed" value={formatTzs(f.billed)} />
        <Kpi label="Collected" value={formatTzs(f.collected)} hint={f.collectionRate != null ? `${Math.round(f.collectionRate * 100)}% of billed` : undefined} tone="success.main" />
        <Kpi label="Outstanding" value={formatTzs(f.outstanding)} tone={f.outstanding > 0 ? 'warning.main' : undefined} />
        <Kpi label="Collected this month" value={formatTzs(f.collectedThisMonth)} hint={`Today ${formatTzs(f.collectedToday)}`} />
      </Grid>
      <Grid container spacing={1.5}>
        <Panel title="Collections, last 30 days" md={7}>
          <ColumnChart ariaLabel="Money collected per day over the last 30 days" format={(v) => `TZS ${short(v)}`}
            points={f.dailyCollections.map((p) => ({ key: p.key, label: p.label, value: Number(p.amount),
              tooltip: `${formatDate(p.key)} · ${p.count} payment${p.count === 1 ? '' : 's'}` }))} />
        </Panel>
        <Panel title="Owed, by how long overdue" md={5}>
          <BarList rows={f.ageing.map((b) => ({ key: b.key, label: b.label, value: Number(b.amount), display: formatTzs(b.amount),
            note: `${b.count} invoice${b.count === 1 ? '' : 's'}` }))} emptyText="Nothing owed." />
        </Panel>
      </Grid>
    </Section>
  );
}
