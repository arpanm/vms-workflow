import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock("@/lib/api-client", () => ({ apiClient: apiMocks }));

import { certificationApi } from "./api";
import type {
  CertificationRequest,
  ClarificationRequest,
  ConfirmationActionRequest,
  ConfirmationRequestInput,
  InboundMessageReviewRequest,
  ManualEvidenceReviewRequest,
  ReopenRequestInput,
  SaveSubmissionRequest,
  SummaryRequest,
} from "./contracts";

const saveInput: SaveSubmissionRequest = {
  expectedMonthVersion: 7,
  summary: "Exact vendor outcome",
  declarationAccepted: true,
  items: [
    {
      deliverableId: "deliverable-1",
      outcome: "COMPLETED",
      completionPercentage: 100,
      completionDate: "2026-08-28",
      summary: "Delivered against the frozen baseline.",
      criterionResponses: [
        {
          criterionId: "criterion-1",
          response: "The regression evidence matches the criterion.",
          evidenceReferenceIds: ["artifact-1"],
        },
      ],
      evidenceReferenceIds: ["artifact-1"],
    },
  ],
};

function headersFromPost(callIndex: number) {
  return apiMocks.post.mock.calls[callIndex]?.[2]?.headers as Record<string, string>;
}

function intentKey(index: number) {
  return `00000000-0000-4000-8000-${String(index).padStart(12, "0")}`;
}

describe("certification API contract", () => {
  beforeEach(() => {
    apiMocks.get.mockReset();
    apiMocks.post.mockReset();
    apiMocks.get.mockResolvedValue({});
    apiMocks.post.mockResolvedValue({});
  });

  it("[F04-UNIT-API-001] scopes and URL-encodes every certification read", async () => {
    await certificationApi.month("month/one");
    await certificationApi.readiness("month/one");
    await certificationApi.confirmationRequest("request/one");

    expect(apiMocks.get.mock.calls).toEqual([
      ["/certification/months/month%2Fone"],
      ["/certification/months/month%2Fone/readiness"],
      ["/certification/confirmation-requests/request%2Fone"],
    ]);
  });

  it("[F04-UNIT-API-002] sends expected-version and idempotency headers on every mutation", async () => {
    const clarification: ClarificationRequest = {
      expectedSubmissionVersion: 3,
      deliverableId: "deliverable-1",
      questions: ["Which scan-passed evidence supports the criterion?"],
    };
    const certification: CertificationRequest = {
      expectedSubmissionVersion: 3,
      deliverableId: "deliverable-1",
      decision: "ACCEPTED",
      criterionResults: [
        {
          criterionId: "criterion-1",
          decision: "MET",
          rationale: "Viewed exact immutable evidence.",
          evidenceViewed: true,
        },
      ],
    };
    const summary: SummaryRequest = {
      expectedMonthVersion: 8,
      decision: "CERTIFIED",
    };
    const confirmation: ConfirmationRequestInput = {
      expectedMonthVersion: 8,
      dueAt: "2026-08-31T13:00:00.000Z",
    };
    const action: ConfirmationActionRequest = {
      expectedRequestVersion: 2,
      decision: "CONFIRM",
    };
    const inboundReview: InboundMessageReviewRequest = {
      expectedReviewVersion: 0,
      decision: "ACCEPT_INTERPRETATION",
      reasoning: "The redacted interpretation matches the explicit permitted phrase.",
    };
    const manualReview: ManualEvidenceReviewRequest = {
      expectedReviewVersion: 0,
      decision: "APPROVE",
      reasoning: "A distinct reviewer verified the safe immutable metadata.",
    };
    const reopen: ReopenRequestInput = {
      expectedMonthVersion: 9,
      category: "CERTIFICATION_CORRECTION",
      reason: "A source correction needs governed review.",
      impactedRecordIds: ["summary-1"],
      packageInvoiceImpact: "Invalidate only the current readiness handoff.",
      riskStatement: "Prior confirmation remains immutable and superseded.",
    };

    await certificationApi.saveSubmission("month/one", saveInput, intentKey(1));
    await certificationApi.submit("submission/one", 3, intentKey(2));
    await certificationApi.requestClarification("submission/one", clarification, intentKey(3));
    await certificationApi.certify("submission/one", certification, intentKey(4));
    await certificationApi.createSummary("month/one", summary, intentKey(5));
    await certificationApi.requestConfirmation("month/one", confirmation, intentKey(6));
    await certificationApi.actOnConfirmation("request/one", action, intentKey(7));
    await certificationApi.reviewInboundMessage("inbound/one", inboundReview, intentKey(8));
    await certificationApi.reviewManualEvidence("manual/one", manualReview, intentKey(9));
    await certificationApi.requestReopen("month/one", reopen, intentKey(10));

    expect(apiMocks.post).toHaveBeenNthCalledWith(
      1,
      "/certification/months/month%2Fone/submissions",
      saveInput,
      expect.any(Object),
    );
    expect(apiMocks.post).toHaveBeenNthCalledWith(
      2,
      "/certification/submissions/submission%2Fone/submit",
      { expectedSubmissionVersion: 3 },
      expect.any(Object),
    );
    expect(apiMocks.post).toHaveBeenNthCalledWith(
      8,
      "/certification/inbound-messages/inbound%2Fone/reviews",
      inboundReview,
      expect.any(Object),
    );
    expect(apiMocks.post).toHaveBeenNthCalledWith(
      9,
      "/certification/manual-evidence/manual%2Fone/reviews",
      manualReview,
      expect.any(Object),
    );
    expect(apiMocks.post).toHaveBeenNthCalledWith(
      10,
      "/certification/months/month%2Fone/reopen-requests",
      reopen,
      expect.any(Object),
    );

    const versions = [7, 3, 3, 3, 8, 8, 2, 0, 0, 9];
    versions.forEach((version, index) => {
      const headers = headersFromPost(index);
      expect(headers["If-Match"]).toBe(`"${version}"`);
      expect(headers["Idempotency-Key"]).toBe(intentKey(index + 1));
    });
  });

  it("[F04-UNIT-API-003] never places token, MIME, recipient authority, or provider secrets in mutation payloads", async () => {
    await certificationApi.actOnConfirmation(
      "request-1",
      {
        expectedRequestVersion: 2,
        decision: "REQUEST_CORRECTION",
        comment: "Correct the disclosed attendance snapshot.",
      },
      intentKey(1),
    );

    const serialized = JSON.stringify(apiMocks.post.mock.calls[0]);
    expect(serialized).not.toMatch(
      /plaintext.?token|token.?hash|raw.?mime|provider.?secret|private.?key|recipient.?authority/i,
    );
    expect(apiMocks.post.mock.calls[0]?.[1]).toEqual({
      expectedRequestVersion: 2,
      decision: "REQUEST_CORRECTION",
      comment: "Correct the disclosed attendance snapshot.",
    });
  });
});
