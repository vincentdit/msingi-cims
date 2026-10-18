/** Msingi billing shapes (as the API sends them) and labels shared by the platform console and the institution's page. */

export interface Channel { provider: string; accountName?: string; accountNumber: string; branch?: string }
export interface BillingSettings {
  companyName: string; tin?: string; vrn?: string; address?: string; phone?: string; email?: string; website?: string; channels: Channel[];
  invoiceNote?: string; updatedByName?: string; updatedAt?: string;
}
export interface Contract {
  id: number; tenantId: number; reference?: string; status: 'ACTIVE' | 'ENDED'; startDate: string; endDate?: string; currency: string;
  setupFee: number; annualFee: number; studentFee: number; applicationFee: number; applicationPercent: number; usageCycle: Cycle;
  vatPercent: number; paymentTermsDays: number; autoIssue: boolean; autoSuspend: boolean; suspendAfterDays: number;
  billingContactName?: string; billingEmail?: string; billingPhone?: string; notes?: string; setupBilled: boolean;
  licenceBilledUntil?: string; usageBilledUntil?: string; createdByName?: string; createdAt?: string;
}
export type Cycle = 'MONTHLY' | 'QUARTERLY' | 'SEMI_ANNUAL' | 'ANNUAL';
export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'PARTLY_PAID' | 'PAID' | 'CANCELLED';
export interface InvoiceRow {
  id: number; tenantId: number; institutionCode: string; institutionName: string; invoiceNo?: string; kind: 'LICENCE' | 'USAGE' | 'OTHER';
  periodFrom?: string; periodTo?: string; status: InvoiceStatus; currency: string; total: number; paidAmount: number; balance: number;
  issueDate?: string; dueDate?: string; daysOverdue?: number; paymentReference?: string; generated: boolean; createdAt: string;
}
export interface Line { kind: string; description: string; quantity: number; unitPrice: number; amount?: number }
export interface InvoiceView {
  invoice: InvoiceRow; lines: Line[]; subtotal: number; vatPercent: number; vatAmount: number; notes?: string;
  payments: { paymentId: number; receiptNo: string; paidAt: string; method: string; amount: number }[];
  reminders: { level: number; kind: string; sentTo?: string; createdAt: string }[]; cancelledReason?: string; createdByName?: string; issuedByName?: string;
}
export interface PaymentRow {
  id: number; tenantId: number; institutionCode: string; institutionName: string; receiptNo: string; amount: number; unallocated: number;
  paidAt: string; method: string; provider?: string; reference?: string; source: 'MANUAL' | 'ONLINE'; status: 'RECEIVED' | 'REVERSED';
  recordedByName?: string; invoices?: string; reversedReason?: string;
}
export interface Aging { current: number; days1to30: number; days31to60: number; days61to90: number; over90: number }
export interface Statement {
  tenantId: number; institutionCode: string; institutionName: string; contract?: Contract;
  entries: { date: string; kind: 'INVOICE' | 'PAYMENT' | 'REVERSAL'; reference: string; description: string; debit: number; credit: number; balance: number }[];
  invoiced: number; paid: number; balance: number; credit: number; aging: Aging;
}
export interface Usage { from: string; to: string; activeStudents: number; paidApplications: number; applicationFeesCollected: number }
export interface Account {
  contract?: Contract; balance: number; overdue: number; credit: number; invoices: InvoiceRow[]; payments: PaymentRow[]; payTo: BillingSettings; canSimulate: boolean;
}

/** Tanzanian shillings as people write them: 1,250,000 (cents only when there are any). */
export const tzs = (n?: number | null) => (n ?? 0).toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 });

export const STATUS_LABEL: Record<InvoiceStatus, string> = { DRAFT: 'Draft', ISSUED: 'Issued', PARTLY_PAID: 'Partly paid', PAID: 'Paid', CANCELLED: 'Cancelled' };
export const STATUS_COLOUR: Record<InvoiceStatus, 'default' | 'info' | 'warning' | 'success' | 'error'> = {
  DRAFT: 'default', ISSUED: 'info', PARTLY_PAID: 'warning', PAID: 'success', CANCELLED: 'default',
};
export const KIND_LABEL: Record<string, string> = { LICENCE: 'Licence', USAGE: 'Usage', OTHER: 'Other' };
export const LINE_KIND: Record<string, string> = {
  SETUP: 'Set-up', LICENCE: 'Licence', STUDENTS: 'Students', APPLICATIONS: 'Applications', APPLICATION_SHARE: 'Share of fees', OTHER: 'Other', DISCOUNT: 'Discount',
};
export const CYCLE_LABEL: Record<Cycle, string> = { MONTHLY: 'Every month', QUARTERLY: 'Every 3 months', SEMI_ANNUAL: 'Every 6 months', ANNUAL: 'Every year' };
export const METHOD_LABEL: Record<string, string> = { BANK: 'Bank transfer', MOBILE_MONEY: 'Mobile money', CHEQUE: 'Cheque', CASH: 'Cash', OTHER: 'Other' };

/** Where Msingi can be paid (the same list as institutions' own accounts). */
export const PROVIDERS: { code: string; label: string; kind: 'BANK' | 'MOBILE_MONEY' }[] = [
  { code: 'MPESA', label: 'M-Pesa', kind: 'MOBILE_MONEY' }, { code: 'MIXX_BY_YAS', label: 'Mixx by Yas', kind: 'MOBILE_MONEY' },
  { code: 'AIRTEL_MONEY', label: 'Airtel Money', kind: 'MOBILE_MONEY' }, { code: 'HALOPESA', label: 'HaloPesa', kind: 'MOBILE_MONEY' },
  { code: 'CRDB', label: 'CRDB Bank', kind: 'BANK' }, { code: 'NMB', label: 'NMB Bank', kind: 'BANK' }, { code: 'NBC', label: 'NBC Bank', kind: 'BANK' },
  { code: 'STANBIC', label: 'Stanbic Bank', kind: 'BANK' }, { code: 'EXIM', label: 'Exim Bank', kind: 'BANK' }, { code: 'ABSA', label: 'Absa Bank', kind: 'BANK' },
  { code: 'AZANIA', label: 'Azania Bank', kind: 'BANK' }, { code: 'DTB', label: 'DTB', kind: 'BANK' }, { code: 'EQUITY', label: 'Equity Bank', kind: 'BANK' },
  { code: 'KCB', label: 'KCB Bank', kind: 'BANK' }, { code: 'TCB', label: 'TCB Bank', kind: 'BANK' }, { code: 'PBZ', label: 'PBZ', kind: 'BANK' },
  { code: 'OTHER_MOBILE', label: 'Other mobile money', kind: 'MOBILE_MONEY' }, { code: 'OTHER_BANK', label: 'Other bank', kind: 'BANK' },
];
export const providerLabel = (code?: string) => PROVIDERS.find((p) => p.code === code)?.label ?? code ?? '';

/** "1 Jan 2027 – 31 Mar 2027" */
export const period = (from?: string, to?: string) => (from && to ? `${day(from)} – ${day(to)}` : '—');
export const day = (iso?: string) => {
  if (!iso) return '—';
  const sw = document.documentElement.lang === 'sw';
  return new Date(`${iso.slice(0, 10)}T12:00:00`).toLocaleDateString(sw ? 'sw-TZ' : 'en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
};
