/** Helpdesk shapes, as the API sends them. */
export interface Attachment { id: number; fileName: string; contentType: string; sizeBytes: number }
export interface Message {
  id: number; authorType?: 'REQUESTER' | 'STAFF' | 'SYSTEM' | 'ASSISTANT'; authorSide?: 'INSTITUTION' | 'MSINGI' | 'SYSTEM';
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
  /** INSTANT: an answer was given at once and the requester has not said whether it solved it. */
  suggestionOutcome?: 'INSTANT' | 'ACCEPTED' | 'REJECTED';
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

/** What the assistant predicts from what someone is writing. */
export interface AnswerHit { id: number; title: string; body: string; categoryId?: number; categoryName?: string; confidence: number }
export interface CategoryGuess { id: number; name: string; confidence: number }
export interface Suggestion { answers: AnswerHit[]; category?: CategoryGuess }
export interface SuggestedReply {
  source: 'ANSWER' | 'PAST_REPLY'; sourceId: number; title: string; text: string; confidence: number; confirmed: boolean; ticketNo?: string;
}
export interface StaffSuggestions { replies: SuggestedReply[]; category?: CategoryGuess }
export interface Gap {
  topic: string; requests: number; categoryName?: string; categoryId?: number; examples: string[]; question: string; answer?: string; latest: string;
}
export interface AssistantStats {
  solvedBeforeAsking: number; instantAnswers: number; instantAccepted: number; instantRejected: number; categoryGuessed: number;
  categoryRight: number; learnedReplies: number;
}
