import { apiClient } from "@/lib/api-client";

import type {
  CertificationInboxView,
  CertificationOperationsView,
  CertificationRequest,
  ClarificationRequest,
  ConfirmationActionRequest,
  ConfirmationRequestInput,
  ConfirmationRequestView,
  InboundMessageReviewRequest,
  InboundReviewView,
  ManualEvidenceReviewRequest,
  MonthCertificationView,
  NotificationReplayRequest,
  NotificationReplayView,
  ReadinessView,
  ReopenRequestInput,
  SaveSubmissionRequest,
  SummaryRequest,
} from "./contracts";

const root = "/certification";
const encoded = (value: string) => encodeURIComponent(value);

function mutationHeaders(expectedVersion: number, idempotencyKey: string) {
  return {
    "If-Match": `"${expectedVersion}"`,
    "Idempotency-Key": idempotencyKey,
  };
}

export const certificationApi = {
  inbox: (limit = 50) =>
    apiClient.get<CertificationInboxView>(
      `${root}/inbox?limit=${encodeURIComponent(String(limit))}`,
    ),

  operations: (limit = 100) =>
    apiClient.get<CertificationOperationsView>(
      `${root}/operations?limit=${encodeURIComponent(String(limit))}`,
    ),

  replayNotification: (input: NotificationReplayRequest, idempotencyKey: string) =>
    apiClient.post<NotificationReplayView>(
      `${root}/notifications/${encoded(input.notificationId)}/replays`,
      {
        expectedMonthVersion: input.expectedMonthVersion,
        reason: input.reason,
      },
      {
        headers: mutationHeaders(input.expectedMonthVersion, idempotencyKey),
      },
    ),

  month: (monthId: string) =>
    apiClient.get<MonthCertificationView>(`${root}/months/${encoded(monthId)}`),

  uploadArtifact: (
    monthId: string,
    file: File,
    classification: "PUBLIC" | "INTERNAL" | "CONFIDENTIAL" | "RESTRICTED",
  ) => {
    const body = new FormData();
    body.set("file", file);
    return apiClient.post<{
      id: string;
      monthId: string;
      displayName: string;
      classification: string;
      scanStatus: string;
      sizeBytes: number;
      sha256: string;
      recordedAt: string;
    }>(
      `${root}/months/${encoded(monthId)}/artifacts?classification=${encoded(classification)}`,
      body,
    );
  },

  scanArtifact: (artifactId: string) =>
    apiClient.post<{
      id: string;
      displayName: string;
      scanStatus: string;
    }>(`${root}/artifacts/${encoded(artifactId)}/scans`),

  saveSubmission: (monthId: string, input: SaveSubmissionRequest, idempotencyKey: string) =>
    apiClient.post<MonthCertificationView>(
      `${root}/months/${encoded(monthId)}/submissions`,
      input,
      { headers: mutationHeaders(input.expectedMonthVersion, idempotencyKey) },
    ),

  submit: (submissionId: string, expectedVersion: number, idempotencyKey: string) =>
    apiClient.post<MonthCertificationView>(
      `${root}/submissions/${encoded(submissionId)}/submit`,
      { expectedSubmissionVersion: expectedVersion },
      { headers: mutationHeaders(expectedVersion, idempotencyKey) },
    ),

  withdraw: (
    submissionId: string,
    input: { expectedSubmissionVersion: number; reason: string },
    idempotencyKey: string,
  ) =>
    apiClient.post<MonthCertificationView>(
      `${root}/submissions/${encoded(submissionId)}/withdraw`,
      input,
      { headers: mutationHeaders(input.expectedSubmissionVersion, idempotencyKey) },
    ),

  requestClarification: (
    submissionId: string,
    input: ClarificationRequest,
    idempotencyKey: string,
  ) =>
    apiClient.post<MonthCertificationView>(
      `${root}/submissions/${encoded(submissionId)}/clarifications`,
      input,
      { headers: mutationHeaders(input.expectedSubmissionVersion, idempotencyKey) },
    ),

  certify: (submissionId: string, input: CertificationRequest, idempotencyKey: string) =>
    apiClient.post<MonthCertificationView>(
      `${root}/submissions/${encoded(submissionId)}/certifications`,
      input,
      { headers: mutationHeaders(input.expectedSubmissionVersion, idempotencyKey) },
    ),

  createSummary: (monthId: string, input: SummaryRequest, idempotencyKey: string) =>
    apiClient.post<MonthCertificationView>(`${root}/months/${encoded(monthId)}/summaries`, input, {
      headers: mutationHeaders(input.expectedMonthVersion, idempotencyKey),
    }),

  readiness: (monthId: string) =>
    apiClient.get<ReadinessView>(`${root}/months/${encoded(monthId)}/readiness`),

  requestConfirmation: (monthId: string, input: ConfirmationRequestInput, idempotencyKey: string) =>
    apiClient.post<ConfirmationRequestView>(
      `${root}/months/${encoded(monthId)}/confirmation-requests`,
      input,
      { headers: mutationHeaders(input.expectedMonthVersion, idempotencyKey) },
    ),

  confirmationRequest: (requestId: string) =>
    apiClient.get<ConfirmationRequestView>(`${root}/confirmation-requests/${encoded(requestId)}`),

  actOnConfirmation: (
    requestId: string,
    input: ConfirmationActionRequest,
    idempotencyKey: string,
  ) =>
    apiClient.post<ConfirmationRequestView>(
      `${root}/confirmation-requests/${encoded(requestId)}/actions`,
      input,
      { headers: mutationHeaders(input.expectedRequestVersion, idempotencyKey) },
    ),

  reviewInboundMessage: (
    reviewId: string,
    input: InboundMessageReviewRequest,
    idempotencyKey: string,
  ) =>
    apiClient.post<InboundReviewView>(
      `${root}/inbound-messages/${encoded(reviewId)}/reviews`,
      input,
      { headers: mutationHeaders(input.expectedReviewVersion, idempotencyKey) },
    ),

  reviewManualEvidence: (
    reviewId: string,
    input: ManualEvidenceReviewRequest,
    idempotencyKey: string,
  ) =>
    apiClient.post<InboundReviewView>(
      `${root}/manual-evidence/${encoded(reviewId)}/reviews`,
      input,
      { headers: mutationHeaders(input.expectedReviewVersion, idempotencyKey) },
    ),

  requestReopen: (monthId: string, input: ReopenRequestInput, idempotencyKey: string) =>
    apiClient.post<MonthCertificationView>(
      `${root}/months/${encoded(monthId)}/reopen-requests`,
      input,
      { headers: mutationHeaders(input.expectedMonthVersion, idempotencyKey) },
    ),
};
