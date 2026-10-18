/** Helpdesk shapes, as the API sends them. */
export interface Attachment { id: number; fileName: string; contentType: string; sizeBytes: number }
export interface Message {
  id: number; authorType?: 'REQUESTER' | 'STAFF' | 'SYSTEM'; authorSide?: 'INSTITUTION' | 'MSINGI' | 'SYSTEM';
  authorName: string; body: string; internal: boolean; createdAt: string; attachments: Attachment[];
}
export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'WAITING' | 'RESOLVED' | 'CLOSED';
export type Priority = 'NORMAL' | 'HIGH' | 'URGENT';
export interface TicketRow {
  id: number; ticketNo: string; subject: string; categoryId: number; categoryName: string; officeRole: string; status: TicketStatus;
  priority: Priority; requesterType: 'APPLICANT' | 'STUDENT' | 'STAFF' | 'GUEST'; requesterName: string; assigneeUserId?: number;
  assigneeName?: string; responseDueAt: string; resolutionDueAt: string; firstResponseAt?: string; resolvedAt?: string; closedAt?: string;
  late?: 'RESPONSE' | 'RESOLUTION'; escalationLevel: number; satisfied?: boolean; createdAt: string; updatedAt: string; messages: number;
}
export interface TicketView {
  ticket: TicketRow; requesterPhone?: string; requesterEmail?: string; messages: Message[]; canReply: boolean; canClose: boolean;
  canManage: boolean; msingiTicketNo?: string; msingiTicketId?: number;
}
export interface Category {
  id: number; name: string; description?: string; officeRole: string; forApplicants: boolean; forStudents: boolean; forStaff: boolean;
  forGuests: boolean; responseHours: number; resolutionHours: number; active: boolean; sortOrder: number; openTickets: number;
}
export interface Article {
  id: number; categoryId?: number; categoryName?: string; title: string; body: string; forApplicants: boolean; forStudents: boolean;
  forStaff: boolean; forPublic: boolean; published: boolean; views: number; helpful: number; notHelpful: number; updatedByName?: string;
  updatedAt: string; canEdit: boolean;
}
export interface SupportRow {
  id: number; ticketNo: string; tenantId: number; institutionCode: string; institutionName: string; category: string; subject: string;
  status: TicketStatus; priority: Priority; raisedByName: string; assigneeId?: number; assigneeName?: string; responseDueAt: string;
  resolutionDueAt: string; firstResponseAt?: string; resolvedAt?: string; late?: 'RESPONSE' | 'RESOLUTION'; satisfied?: boolean;
  helpdeskTicketNo?: string; createdAt: string; updatedAt: string;
}
export interface SupportView { ticket: SupportRow; raisedByEmail?: string; raisedByPhone?: string; messages: Message[]; canReply: boolean; canClose: boolean }
export interface Counts { open: number; mine: number; unassigned: number; overdue: number }
