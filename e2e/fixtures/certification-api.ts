import type { Page, Route } from "@playwright/test";

type RecordedRequest = {
  method: string;
  path: string;
  search: string;
  headers: Record<string, string>;
  body: Record<string, unknown>;
};

type MonthScenario = "draft" | "complete-draft" | "submitted" | "stale";
type ReadinessScenario = "ready" | "blocked" | "stale";
type ConfirmationScenario = "active" | "replayed" | "expired" | "unauthorized";

export type CertificationApiOptions = {
  monthScenario?: MonthScenario;
  readinessScenario?: ReadinessScenario;
  confirmationScenario?: ConfirmationScenario;
  saveConflict?: boolean;
  unsafeMonthError?: boolean;
  loseFirstActionResponse?: boolean;
  multiProjectAction?: boolean;
  defaultDueAt?: string;
  includeUnassignedReview?: boolean;
  inboundAccess?: "authorized" | "unauthorized";
};

const ids = {
  month: "f0400000-0000-0000-0000-000000000001",
  engagement: "f0400000-0000-0000-0000-000000000002",
  baseline: "f0400000-0000-0000-0000-000000000003",
  baselineVersion: "f0400000-0000-0000-0000-000000000004",
  submission: "f0400000-0000-0000-0000-000000000005",
  deliverable: "f0400000-0000-0000-0000-000000000006",
  criterion: "f0400000-0000-0000-0000-000000000007",
  evidence: "f0400000-0000-0000-0000-000000000008",
  clarification: "f0400000-0000-0000-0000-000000000009",
  summary: "f0400000-0000-0000-0000-000000000010",
  requestV1: "f0400000-0000-0000-0000-000000000011",
  request: "f0400000-0000-0000-0000-000000000012",
  requestV3: "f0400000-0000-0000-0000-000000000013",
  action: "f0400000-0000-0000-0000-000000000014",
  inbound: "f0400000-0000-0000-0000-000000000015",
  linearPlan: "f0400000-0000-0000-0000-000000000016",
  linearMonthEnd: "f0400000-0000-0000-0000-000000000017",
  linearCurrent: "f0400000-0000-0000-0000-000000000018",
  notificationRead: "f0400000-0000-0000-0000-000000000019",
  notificationDeadLetter: "f0400000-0000-0000-0000-000000000020",
  notificationDelivered: "f0400000-0000-0000-0000-000000000021",
  noticeCorrelation: "f0400000-0000-0000-0000-000000000022",
  timelineDraft: "f0400000-0000-0000-0000-000000000023",
  timelineSubmit: "f0400000-0000-0000-0000-000000000024",
  timelineReopen: "f0400000-0000-0000-0000-000000000025",
  draftCorrelation: "f0400000-0000-0000-0000-000000000026",
  submitCorrelation: "f0400000-0000-0000-0000-000000000027",
  reopenCorrelation: "f0400000-0000-0000-0000-000000000028",
  clarificationRound2: "f0400000-0000-0000-0000-000000000029",
  certification: "f0400000-0000-0000-0000-000000000030",
  actionAudit: "f0400000-0000-0000-0000-000000000031",
  browserUser: "f0400000-0000-0000-0000-000000000032",
  organization: "f0400000-0000-0000-0000-000000000033",
  projectA: "f0400000-0000-0000-0000-000000000034",
  projectB: "f0400000-0000-0000-0000-000000000035",
  attendanceSnapshot: "f0400000-0000-0000-0000-000000000036",
  deliveryPlanVersion: "f0400000-0000-0000-0000-000000000037",
  manualEvidence: "f0400000-0000-0000-0000-000000000038",
  quarantinedInbound: "f0400000-0000-0000-0000-000000000039",
  inboundAudit: "f0400000-0000-0000-0000-000000000040",
  manualEvidenceAudit: "f0400000-0000-0000-0000-000000000041",
  quarantineAudit: "f0400000-0000-0000-0000-000000000042",
  unassignedDeliverable: "f0400000-0000-0000-0000-000000000043",
  unassignedCriterion: "f0400000-0000-0000-0000-000000000044",
  unassignedCertification: "f0400000-0000-0000-0000-000000000045",
  unassignedBaselineVersion: "f0400000-0000-0000-0000-000000000046",
};

const safeEvidence = {
  id: ids.evidence,
  displayName: "regression-report.pdf",
  classification: "INTERNAL",
  scanStatus: "PASSED",
  source: "ARTIFACT",
  viewAllowed: false,
};

const permissions = {
  canEditSubmission: true,
  canSubmit: true,
  canRespondToClarification: true,
  canCertify: true,
  canRequestClarification: true,
  canGenerateSummary: true,
  canRequestConfirmation: true,
  canConfirm: true,
  canReviewInbound: true,
  canReopen: true,
};

function submissionItem(complete = true) {
  return {
    deliverableId: ids.deliverable,
    outcome: "PARTIALLY_COMPLETED",
    completionPercentage: 80,
    completionDate: "2026-08-28",
    summary: "The core scope is complete; the export remains governed carry-forward.",
    varianceCause: "The approved client test dataset arrived after the cut-off.",
    varianceImpact: "The export verification moves to the next governed month.",
    nextAction: "Validate the export against the approved test dataset.",
    carryForwardProposal: "Product owner and vendor owner review by 2026-09-05.",
    criterionResponses: [
      {
        criterionId: ids.criterion,
        response: complete
          ? "The exact baseline behavior passed the browser and API regression."
          : "",
        evidenceReferences: complete ? [safeEvidence] : [],
      },
    ],
    evidenceReferences: complete ? [safeEvidence] : [],
  };
}

function submission(scenario: MonthScenario) {
  const complete = scenario !== "draft";
  const locked = scenario === "submitted";
  return {
    id: ids.submission,
    version: 3,
    status: locked ? "SUBMITTED" : "DRAFT",
    summary: complete
      ? "August exact-version delivery evidence."
      : "Draft awaiting required evidence.",
    declarationAccepted: complete,
    completenessBlockers: complete
      ? []
      : [
          "Every mandatory criterion needs a response.",
          "Required evidence must be scan-passed before submission.",
        ],
    autosavedAt: "2026-08-29T09:00:00Z",
    submittedAt: locked ? "2026-08-30T10:00:00Z" : null,
    locked,
    items: [submissionItem(complete)],
  };
}

function deliverable(scenario: MonthScenario) {
  const reviewStarted = scenario === "submitted";
  return {
    id: ids.deliverable,
    code: "DEL-F04-001",
    title: "Exact certification evidence",
    projectName: "Cadence Governance",
    baselineVersionId: ids.baselineVersion,
    baselineDescription: "Publish attributable certification and confirmation evidence.",
    businessObjective: "Require explicit authorized decisions without Linear inference.",
    evidenceExpectation: "Scan-passed regression report and criterion response.",
    assignedToCurrentActor: true,
    assignmentReason: "Frozen product-owner assignment",
    reviewStartedAt: reviewStarted ? "2026-08-29T10:00:00Z" : null,
    reviewDueAt: reviewStarted ? "2026-08-30T10:00:00Z" : null,
    reviewAgeSeconds: reviewStarted ? 172_800 : 0,
    reviewAgingStatus: reviewStarted ? "OVERDUE" : "NOT_STARTED",
    criteria: [
      {
        id: ids.criterion,
        sequence: 1,
        statement: "The exact immutable version is reviewable.",
        expectedResult: "Baseline, vendor claim, evidence, and decision remain attributable.",
        mandatory: true,
      },
    ],
    vendorSubmission: submissionItem(scenario !== "draft"),
    certification: null,
  };
}

function unassignedResolvedDeliverable() {
  const base = deliverable("submitted");
  const vendorSubmission = {
    ...base.vendorSubmission,
    deliverableId: ids.unassignedDeliverable,
    criterionResponses: [
      {
        ...base.vendorSubmission.criterionResponses[0],
        criterionId: ids.unassignedCriterion,
      },
    ],
  };
  return {
    ...base,
    id: ids.unassignedDeliverable,
    code: "DEL-F04-UNASSIGNED",
    title: "Unassigned payroll integration evidence",
    baselineVersionId: ids.unassignedBaselineVersion,
    assignedToCurrentActor: false,
    assignmentReason: null,
    reviewAgingStatus: "RESOLVED",
    criteria: [
      {
        ...base.criteria[0],
        id: ids.unassignedCriterion,
      },
    ],
    vendorSubmission,
    certification: {
      id: ids.unassignedCertification,
      version: 1,
      decision: "ACCEPTED",
      comment: null,
      observations: null,
      cause: null,
      nextAction: null,
      acceptedScope: null,
      rejectedScope: null,
      carryForward: null,
      criterionResults: [
        {
          criterionId: ids.unassignedCriterion,
          decision: "MET",
          rationale: "A different assigned owner recorded the exact decision.",
          evidenceViewed: true,
        },
      ],
      decidedByDisplay: "Different Product Owner",
      decidedAt: "2026-08-30T11:00:00Z",
      terminal: true,
    },
  };
}

function notification(
  transportStatus:
    | "NOT_CONFIGURED"
    | "QUEUED"
    | "SENT"
    | "DELIVERED"
    | "READ"
    | "BOUNCED"
    | "FAILED"
    | "DEAD_LETTER" = "READ",
) {
  return {
    id:
      transportStatus === "READ"
        ? ids.notificationRead
        : transportStatus === "DEAD_LETTER"
          ? ids.notificationDeadLetter
          : ids.notificationDelivered,
    category: "CONFIRMATION_REQUEST",
    businessState: "AWAITING_RESPONSE",
    transportStatus,
    recipientSummary: "Exact snapshotted recipient categories",
    createdAt: "2026-08-30T10:00:00Z",
    lastAttemptAt: "2026-08-30T10:01:00Z",
    errorCategory: transportStatus === "DEAD_LETTER" ? "PROVIDER_NOT_CONFIGURED" : null,
    correlationId: ids.noticeCorrelation,
  };
}

function confirmationHistory() {
  return [
    {
      id: ids.requestV1,
      version: 1,
      state: "SUPERSEDED",
      dueAt: "2026-08-31T18:30:00+05:30",
      createdAt: "2026-08-29T10:00:00Z",
      supersedesRequestId: null,
    },
    {
      id: ids.request,
      version: 2,
      state: "AWAITING_RESPONSE",
      dueAt: "2026-08-31T18:30:00+05:30",
      createdAt: "2026-08-30T10:00:00Z",
      supersedesRequestId: ids.requestV1,
    },
  ];
}

function monthView(scenario: MonthScenario) {
  const stale = scenario === "stale";
  const locked = scenario === "submitted";
  return {
    monthId: ids.month,
    engagementId: ids.engagement,
    monthLabel: "August 2026",
    lifecycleState: locked ? "DELIVERY_SUBMITTED" : "DELIVERY_DRAFT",
    version: 7,
    stale,
    locked: false,
    lastEvaluatedAt: "2026-08-30T09:30:00Z",
    baseline: {
      id: ids.baseline,
      versionId: ids.baselineVersion,
      checksum: "sha256:f04-exact-frozen-baseline-v3",
      frozen: true,
    },
    permissions,
    evidenceChoices: [safeEvidence],
    submission: submission(scenario),
    deliverables: [deliverable(scenario)],
    clarifications: [
      {
        id: ids.clarification,
        round: 1,
        deliverableId: ids.deliverable,
        questions: ["Identify the scan-passed evidence for the partial export."],
        requestedByDisplay: "Reliance Product Owner",
        requestedAt: "2026-08-29T10:00:00Z",
        response: null,
        respondedAt: null,
        status: "OPEN",
      },
    ],
    summary: {
      id: ids.summary,
      version: 2,
      decision: "PARTIALLY_CERTIFIED",
      checksum: "sha256:f04-certification-summary-v2",
      createdAt: "2026-08-30T09:00:00Z",
      observations: "One explicitly accepted carry-forward remains.",
      terminalItemCount: 1,
      totalItemCount: 1,
      superseded: false,
    },
    linearSnapshots: [
      {
        label: "PLAN_TIME",
        status: "CAPTURED",
        freshness: "CURRENT",
        capturedAt: "2026-08-01T00:00:00Z",
        sourceVersionId: ids.linearPlan,
      },
      {
        label: "MONTH_END",
        status: "FETCH_FAILED",
        freshness: "UNKNOWN",
        capturedAt: null,
        sourceVersionId: ids.linearMonthEnd,
      },
      {
        label: "CURRENT",
        status: "CAPTURED",
        freshness: "STALE",
        capturedAt: "2026-08-30T08:00:00Z",
        sourceVersionId: ids.linearCurrent,
      },
    ],
    confirmationPreview: {
      sourceVersionIds: [
        ids.attendanceSnapshot,
        ids.deliveryPlanVersion,
        ids.baselineVersion,
        ids.summary,
      ],
      toRecipients: [
        {
          display: "Reliance Product Owner",
          roleReason: "Eligible product-owner confirmer",
        },
      ],
      ccRecipients: [
        {
          display: "Central Procurement",
          roleReason: "Mandatory Procurement visibility",
        },
      ],
      eligibleConfirmers: [
        {
          display: "Reliance Product Owner",
          roleReason: "Active scoped product owner",
        },
      ],
      quorumDescription: "ANY_ONE · 1 of 1 eligible confirmer",
      defaultDueAt: "2026-08-31T18:30:00+05:30",
      ready: true,
      blockers: [],
    },
    confirmationHistory: confirmationHistory(),
    notifications: [notification("READ"), notification("DEAD_LETTER")],
    timeline: [
      {
        id: ids.timelineDraft,
        label: "Vendor draft saved",
        state: "DRAFT",
        actorDisplay: "ArrowFoundry Vendor Manager",
        recordedAt: "2026-08-29T09:00:00Z",
        representedAt: null,
        correlationId: ids.draftCorrelation,
      },
      ...(locked
        ? [
            {
              id: ids.timelineSubmit,
              label: "Exact submission locked",
              state: "SUBMITTED",
              actorDisplay: "ArrowFoundry Vendor Manager",
              recordedAt: "2026-08-30T10:00:00Z",
              representedAt: null,
              correlationId: ids.submitCorrelation,
            },
          ]
        : []),
    ],
    inboundReviews: [
      {
        id: ids.inbound,
        reviewKind: "INBOUND_MESSAGE",
        source: "AMBIGUOUS_REPLY",
        authenticationConfidence: "PARTIAL",
        reviewStatus: "PENDING",
        senderEligibility: "ELIGIBLE",
        version: 0,
        assignedToCurrentActor: true,
        assignmentReason: "Distinct authorized inbound reviewer",
        representedAt: "2026-08-29T12:00:00Z",
        recordedAt: "2026-08-30T08:00:00Z",
        ageSeconds: 90_000,
        agingStatus: "AGING",
        safeSummary: "Ambiguous reply requires a distinct authorized reviewer.",
        reason: "No explicit confirmation phrase was recorded.",
        auditReference: ids.inboundAudit,
      },
      {
        id: ids.manualEvidence,
        reviewKind: "MANUAL_EVIDENCE",
        source: "MANUAL_EVIDENCE",
        authenticationConfidence: "UNAVAILABLE",
        reviewStatus: "PENDING",
        senderEligibility: "ELIGIBLE",
        version: 0,
        assignedToCurrentActor: true,
        assignmentReason: "Distinct authorized manual-evidence reviewer",
        representedAt: "2026-08-28T12:00:00Z",
        recordedAt: "2026-08-30T08:30:00Z",
        ageSeconds: 93_600,
        agingStatus: "AGING",
        safeSummary: "Manual evidence metadata is awaiting an independent second review.",
        reason: "Recorder and second reviewer must remain distinct.",
        auditReference: ids.manualEvidenceAudit,
      },
      {
        id: ids.quarantinedInbound,
        reviewKind: "INBOUND_MESSAGE",
        source: "QUARANTINED",
        authenticationConfidence: "FAILED",
        reviewStatus: "QUARANTINED",
        senderEligibility: "UNKNOWN",
        version: 1,
        assignedToCurrentActor: false,
        assignmentReason: "Security quarantine",
        representedAt: null,
        recordedAt: "2026-08-30T09:00:00Z",
        ageSeconds: 0,
        agingStatus: "RESOLVED",
        safeSummary: "Unsafe inbound content was quarantined without creating a decision.",
        reason: "Provider signature verification failed.",
        auditReference: ids.quarantineAudit,
      },
    ],
  };
}

function readinessView(scenario: ReadinessScenario) {
  const blocked = scenario === "blocked";
  const stale = scenario === "stale";
  const pillarInputs = [
    ["ROSTER", "Roster and allocation", "roster-snapshot-v2"],
    ["ATTENDANCE", "Attendance", "attendance-snapshot-v4"],
    ["PLAN_LINEAR", "Plan and Linear", ids.baselineVersion],
    ["CERTIFICATION", "Certification", ids.summary],
    ["CONFIRMATION_HANDOFF", "Confirmation and handoff", ids.request],
  ] as const;
  return {
    monthId: ids.month,
    version: 4,
    inputManifestVersion: "readiness-manifest-v4",
    status: blocked ? "BLOCKED" : stale ? "STALE" : "READY",
    evaluatedAt: "2026-08-30T09:30:00Z",
    stale,
    pillars: pillarInputs.map(([key, label, sourceVersionId], index) => ({
      key,
      label,
      status: blocked && index === 1 ? "BLOCKED" : stale && index === 4 ? "STALE" : "READY",
      sourceVersionId,
      freshness: stale && index === 4 ? "STALE" : "CURRENT",
      checkedAt: "2026-08-30T09:30:00Z",
      blockers:
        blocked && index === 1
          ? [
              {
                code: "ATTENDANCE_SNAPSHOT_MISSING",
                message: "Closed attendance snapshot is required.",
                severity: "BLOCKING",
                owner: "Attendance Governance",
                actionLabel: "Open month close",
                actionPath: "/attendance/month-close",
              },
            ]
          : [],
    })),
    blockers: blocked
      ? [
          {
            code: "ATTENDANCE_SNAPSHOT_MISSING",
            message: "Closed attendance snapshot is required.",
            severity: "BLOCKING",
            owner: "Attendance Governance",
            actionLabel: "Open month close",
            actionPath: "/attendance/month-close",
          },
        ]
      : [],
    f05HandoffStatus: stale ? "INVALIDATED" : blocked ? "NOT_ELIGIBLE" : "ELIGIBLE",
  };
}

function actionView(decision: "CONFIRM" | "REQUEST_CORRECTION" = "CONFIRM") {
  return {
    id: ids.action,
    decision,
    actorDisplay: "Reliance Product Owner",
    actorRoleReason: "Active scoped product owner",
    source: "IN_APP",
    comment:
      decision === "REQUEST_CORRECTION"
        ? "Correct the disclosed attendance snapshot."
        : "Reviewed the exact visible version and diff.",
    recordedAt: "2026-08-30T10:05:00Z",
    representedAt: null,
    auditReference: ids.actionAudit,
  };
}

function requestView(scenario: ConfirmationScenario = "active") {
  const replayed = scenario === "replayed";
  const expired = scenario === "expired";
  return {
    id: ids.request,
    monthId: ids.month,
    engagementLabel: "ArrowFoundry × Reliance",
    monthLabel: "August 2026",
    version: replayed ? 3 : 2,
    state: replayed ? "CONFIRMED" : expired ? "EXPIRED" : "AWAITING_RESPONSE",
    dueAt: "2026-08-31T18:30:00+05:30",
    createdAt: "2026-08-30T10:00:00Z",
    locked: replayed || expired,
    stale: false,
    eligible: true,
    eligibilityMessage: "Eligible active scoped product owner",
    projectIdRequired: false,
    eligibleProjects: [
      {
        id: ids.projectA,
        display: "Cadence Governance",
        roleReason: "Captured product-owner assignment",
      },
    ],
    scopeChecksum: "sha256:f04-confirmation-scope-v2",
    sourceVersionIds: [
      ids.attendanceSnapshot,
      ids.deliveryPlanVersion,
      ids.baselineVersion,
      ids.summary,
    ],
    scopeSources: [
      {
        kind: "ATTENDANCE_SNAPSHOT",
        id: ids.attendanceSnapshot,
        version: 4,
        checksum: "sha256:f04-attendance-snapshot-v4",
        freshness: "CURRENT",
        display: "Closed attendance snapshot",
      },
      {
        kind: "DELIVERY_PLAN_VERSION",
        id: ids.deliveryPlanVersion,
        version: 3,
        checksum: "sha256:f04-frozen-delivery-plan-v3",
        freshness: "FROZEN",
        display: "Frozen delivery plan",
      },
      {
        kind: "DELIVERY_BASELINE",
        id: ids.baselineVersion,
        version: 3,
        checksum: "sha256:f04-immutable-delivery-baseline-v3",
        freshness: "FROZEN",
        display: "Immutable delivery baseline",
      },
      {
        kind: "CERTIFICATION_SUMMARY",
        id: ids.summary,
        version: 2,
        checksum: "sha256:f04-certification-summary-v2",
        freshness: "CURRENT",
        display: "Monthly certification summary",
      },
    ],
    recipients: [
      {
        display: "Reliance Product Owner",
        roleReason: "Eligible product-owner confirmer",
        kind: "TO",
      },
      {
        display: "Central Procurement",
        roleReason: "Mandatory Procurement visibility",
        kind: "CC",
      },
    ],
    quorumDescription: "ANY_ONE · 1 of 1 eligible confirmer",
    transportStatus: "DELIVERED",
    providerConfiguration: "NOT_CONFIGURED",
    diff: [
      {
        fieldLabel: "Attendance snapshot",
        previousValue: "attendance-snapshot-v3",
        currentValue: "attendance-snapshot-v4",
      },
      {
        fieldLabel: "Certification summary",
        previousValue: "summary-v1",
        currentValue: ids.summary,
      },
    ],
    actions: replayed ? [actionView()] : [],
    notifications: [notification("DELIVERED")],
    lineage: confirmationHistory(),
    permissions,
  };
}

function postBody(route: Route): Record<string, unknown> {
  const data = route.request().postData();
  if (!data) return {};
  return JSON.parse(data) as Record<string, unknown>;
}

function fulfillJson(route: Route, body: unknown, status = 200, headers?: Record<string, string>) {
  return route.fulfill({
    status,
    contentType: "application/json",
    headers,
    body: JSON.stringify(body),
  });
}

function mutationContractError(
  path: string,
  headers: Record<string, string>,
  body: Record<string, unknown>,
) {
  if (!/^"\d+"$/.test(headers["if-match"] ?? "")) {
    return "A quoted numeric If-Match header is required.";
  }
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
      headers["idempotency-key"] ?? "",
    )
  ) {
    return "A UUID Idempotency-Key header is required.";
  }
  const expectedVersionField = path.endsWith("/submissions")
    ? "expectedMonthVersion"
    : path.endsWith("/submit") ||
        path.endsWith("/clarifications") ||
        path.endsWith("/certifications")
      ? "expectedSubmissionVersion"
      : path.endsWith("/summaries") ||
          path.endsWith("/confirmation-requests") ||
          path.endsWith("/reopen-requests")
        ? "expectedMonthVersion"
        : path.endsWith("/actions")
          ? "expectedRequestVersion"
          : path.endsWith("/reviews")
            ? "expectedReviewVersion"
            : null;
  if (
    expectedVersionField &&
    (!Number.isInteger(body[expectedVersionField]) || (body[expectedVersionField] as number) < 0)
  ) {
    return `${expectedVersionField} is required and must be a non-negative integer.`;
  }
  if (expectedVersionField && headers["if-match"] !== `"${String(body[expectedVersionField])}"`) {
    return `If-Match must equal ${expectedVersionField}.`;
  }
  return null;
}

export async function mockCertificationApi(page: Page, options: CertificationApiOptions = {}) {
  const requests: RecordedRequest[] = [];
  const monthScenario = options.monthScenario ?? "draft";
  const readinessScenario = options.readinessScenario ?? "ready";
  const confirmationScenario = options.confirmationScenario ?? "active";
  let currentMonth = monthView(monthScenario);
  if (options.defaultDueAt) {
    currentMonth = {
      ...currentMonth,
      confirmationPreview: {
        ...currentMonth.confirmationPreview,
        defaultDueAt: options.defaultDueAt,
      },
    };
  }
  if (options.includeUnassignedReview) {
    currentMonth = {
      ...currentMonth,
      deliverables: [...currentMonth.deliverables, unassignedResolvedDeliverable()],
      summary: {
        ...currentMonth.summary,
        terminalItemCount: 1,
        totalItemCount: 2,
      },
    };
  }
  if (options.inboundAccess === "unauthorized") {
    currentMonth = {
      ...currentMonth,
      permissions: {
        ...currentMonth.permissions,
        canReviewInbound: false,
      },
      inboundReviews: [],
    };
  }
  let currentReadiness = readinessView(readinessScenario);
  let currentRequest = requestView(confirmationScenario);
  if (options.multiProjectAction) {
    currentRequest = {
      ...currentRequest,
      projectIdRequired: true,
      eligibleProjects: [
        ...currentRequest.eligibleProjects,
        {
          id: ids.projectB,
          display: "Cadence Reporting",
          roleReason: "Captured product-owner assignment",
        },
      ],
    };
  }
  let conflictPending = Boolean(options.saveConflict);
  let committedLostAction:
    | {
        idempotencyKey: string;
        request: ReturnType<typeof requestView>;
      }
    | undefined;
  let actionBusinessEffects = 0;
  const committedEvidenceReviews = new Map<
    string,
    {
      idempotencyKey: string;
      body: string;
      result: (typeof currentMonth.inboundReviews)[number];
    }
  >();
  let evidenceReviewBusinessEffects = 0;

  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    const body = postBody(route);
    requests.push({
      method,
      path: url.pathname,
      search: url.search,
      headers: request.headers(),
      body,
    });

    if (method === "POST" && url.pathname.startsWith("/api/v1/certification/")) {
      const contractError = mutationContractError(url.pathname, request.headers(), body);
      if (contractError) {
        await fulfillJson(
          route,
          {
            code: "INVALID_REQUEST_CONTRACT",
            message: contractError,
            correlationId: "f0400000-0000-0000-0000-000000000099",
          },
          400,
          { "x-correlation-id": "f0400000-0000-0000-0000-000000000099" },
        );
        return;
      }
    }

    if (url.pathname === "/api/v1/me") {
      await fulfillJson(route, {
        id: ids.browserUser,
        email: "product.owner@reliance.example",
        displayName: "Reliance Product Owner",
        organizationIds: [ids.organization],
        permissions: [
          "catalog.read",
          "certification.read",
          "certification.write",
          "confirmation.read",
          "confirmation.write",
        ],
      });
      return;
    }

    if (url.pathname === "/api/v1/certification/inbox" && method === "GET") {
      const submitted = currentMonth.submission.status === "SUBMITTED";
      await fulfillJson(route, {
        generatedAt: "2026-08-30T10:10:00Z",
        total: 1,
        actionRequired: 1,
        overdue: submitted ? 1 : 0,
        items: [
          {
            monthId: ids.month,
            engagementId: ids.engagement,
            engagementCode: "ENG-CADENCE-001",
            engagementName: "ArrowFoundry × Reliance",
            monthStartDate: "2026-08-01",
            monthLabel: "August 2026",
            lifecycleState: currentMonth.lifecycleState,
            monthVersion: currentMonth.version,
            submissionStatus: currentMonth.submission.status,
            deliverableCount: currentMonth.deliverables.length,
            terminalDecisionCount: currentMonth.summary.terminalItemCount,
            assignedReviewCount: currentMonth.deliverables.filter(
              (item) => item.assignedToCurrentActor && !item.certification?.terminal,
            ).length,
            pendingInboundReviewCount: currentMonth.inboundReviews.filter(
              (item) => item.reviewStatus === "PENDING",
            ).length,
            confirmationState: currentRequest.state,
            confirmationDueAt: currentRequest.dueAt,
            readinessStatus: currentReadiness.status,
            overdue: submitted,
            nextAction: submitted
              ? "CERTIFY_ASSIGNED_DELIVERABLES"
              : "COMPLETE_DELIVERY_SUBMISSION",
            actionPath: `/certification/${ids.month}`,
          },
        ],
      });
      return;
    }

    if (url.pathname === `/api/v1/certification/months/${ids.month}` && method === "GET") {
      if (options.unsafeMonthError) {
        await fulfillJson(
          route,
          {
            code: "NOT_FOUND",
            message: "Internal persistence diagnostic row 42 must never render in the browser.",
            correlationId: "corr-f04-safe-denial",
          },
          404,
          { "x-correlation-id": "corr-f04-safe-denial" },
        );
        return;
      }
      await fulfillJson(route, currentMonth, 200, {
        etag: `"${currentMonth.version}"`,
      });
      return;
    }

    if (
      url.pathname === `/api/v1/certification/months/${ids.month}/readiness` &&
      method === "GET"
    ) {
      await fulfillJson(route, currentReadiness);
      return;
    }

    if (
      url.pathname === `/api/v1/certification/confirmation-requests/${ids.request}` &&
      method === "GET"
    ) {
      if (confirmationScenario === "unauthorized") {
        await fulfillJson(
          route,
          {
            code: "NOT_FOUND",
            message: "The requested record is unavailable.",
            correlationId: "corr-f04-denied",
          },
          404,
          { "x-correlation-id": "corr-f04-denied" },
        );
        return;
      }
      await fulfillJson(route, currentRequest, 200, {
        etag: `"${currentRequest.version}"`,
      });
      return;
    }

    if (
      url.pathname === `/api/v1/certification/months/${ids.month}/submissions` &&
      method === "POST"
    ) {
      if (conflictPending) {
        conflictPending = false;
        currentMonth = {
          ...currentMonth,
          version: currentMonth.version + 1,
          submission: {
            ...currentMonth.submission,
            version: currentMonth.submission.version + 1,
            summary: "Concurrent server summary that must replace stale local fields.",
          },
        };
        await fulfillJson(
          route,
          {
            code: "VERSION_CONFLICT",
            message: "A newer server version exists.",
            correlationId: "corr-f04-conflict",
          },
          412,
          {
            etag: `"${currentMonth.version}"`,
            "x-correlation-id": "corr-f04-conflict",
          },
        );
        return;
      }
      const inputItems = (body.items ?? []) as Array<Record<string, unknown>>;
      const savedItem = inputItems[0] ?? {};
      currentMonth = {
        ...currentMonth,
        version: currentMonth.version + 1,
        submission: {
          ...currentMonth.submission,
          version: currentMonth.submission.version + 1,
          status: "DRAFT",
          summary: String(body.summary ?? ""),
          declarationAccepted: Boolean(body.declarationAccepted),
          completenessBlockers: [],
          autosavedAt: "2026-08-30T09:45:00Z",
          items: [
            {
              ...submissionItem(),
              ...savedItem,
              criterionResponses: (
                (savedItem.criterionResponses ?? []) as Array<Record<string, unknown>>
              ).map((criterion) => ({
                criterionId: criterion.criterionId,
                response: criterion.response,
                evidenceReferences: criterion.evidenceReferenceIds?.includes(ids.evidence)
                  ? [safeEvidence]
                  : [],
              })),
              evidenceReferences: (savedItem.evidenceReferenceIds as string[])?.includes(
                ids.evidence,
              )
                ? [safeEvidence]
                : [],
            },
          ],
        },
      };
      currentMonth.deliverables = [
        {
          ...currentMonth.deliverables[0],
          vendorSubmission: currentMonth.submission.items[0],
        },
      ];
      await fulfillJson(route, currentMonth, 201, {
        etag: `"${currentMonth.version}"`,
      });
      return;
    }

    if (
      url.pathname === `/api/v1/certification/submissions/${ids.submission}/submit` &&
      method === "POST"
    ) {
      currentMonth = {
        ...currentMonth,
        version: currentMonth.version + 1,
        lifecycleState: "DELIVERY_SUBMITTED",
        submission: {
          ...currentMonth.submission,
          version: currentMonth.submission.version + 1,
          status: "SUBMITTED",
          locked: true,
          submittedAt: "2026-08-30T10:00:00Z",
        },
        deliverables: currentMonth.deliverables.map((item) => ({
          ...item,
          reviewStartedAt: "2026-08-30T10:00:00Z",
          reviewDueAt: "2026-08-31T10:00:00Z",
          reviewAgeSeconds: 0,
          reviewAgingStatus: "NEW",
        })),
        timeline: [
          ...currentMonth.timeline,
          {
            id: ids.timelineSubmit,
            label: "Exact submission locked",
            state: "SUBMITTED",
            actorDisplay: "ArrowFoundry Vendor Manager",
            recordedAt: "2026-08-30T10:00:00Z",
            representedAt: null,
            correlationId: ids.submitCorrelation,
          },
        ],
      };
      await fulfillJson(route, currentMonth, 200, {
        etag: `"${currentMonth.version}"`,
      });
      return;
    }

    if (
      url.pathname === `/api/v1/certification/submissions/${ids.submission}/clarifications` &&
      method === "POST"
    ) {
      if (Array.isArray(body.questions)) {
        currentMonth = {
          ...currentMonth,
          submission: { ...currentMonth.submission, status: "CLARIFICATION_REQUIRED" },
          clarifications: [
            ...currentMonth.clarifications,
            {
              id: ids.clarificationRound2,
              round: 2,
              deliverableId: ids.deliverable,
              questions: body.questions,
              requestedByDisplay: "Reliance Product Owner",
              requestedAt: "2026-08-30T10:10:00Z",
              response: null,
              respondedAt: null,
              status: "OPEN",
            },
          ],
        };
      } else {
        currentMonth = {
          ...currentMonth,
          clarifications: currentMonth.clarifications.map((clarification) =>
            clarification.id === body.clarificationId
              ? {
                  ...clarification,
                  response: body.response,
                  respondedAt: "2026-08-30T10:15:00Z",
                  status: "RESPONDED",
                }
              : clarification,
          ),
        };
      }
      await fulfillJson(route, currentMonth, 200, {
        etag: `"${currentMonth.version}"`,
      });
      return;
    }

    if (
      url.pathname === `/api/v1/certification/submissions/${ids.submission}/certifications` &&
      method === "POST"
    ) {
      currentMonth = {
        ...currentMonth,
        version: currentMonth.version + 1,
        deliverables: currentMonth.deliverables.map((item) =>
          item.id === body.deliverableId
            ? {
                ...item,
                reviewAgingStatus:
                  body.decision === "MORE_INFORMATION_REQUIRED"
                    ? item.reviewAgingStatus
                    : "RESOLVED",
                certification: {
                  id: ids.certification,
                  version: 1,
                  decision: body.decision,
                  comment: body.comment ?? null,
                  observations: body.observations ?? null,
                  cause: body.cause ?? null,
                  nextAction: body.nextAction ?? null,
                  acceptedScope: body.acceptedScope ?? null,
                  rejectedScope: body.rejectedScope ?? null,
                  carryForward: body.carryForward ?? null,
                  criterionResults: body.criterionResults ?? [],
                  decidedByDisplay: "Reliance Product Owner",
                  decidedAt: "2026-08-30T10:20:00Z",
                  terminal: body.decision !== "MORE_INFORMATION_REQUIRED",
                },
              }
            : item,
        ),
      };
      await fulfillJson(route, currentMonth, 200, {
        etag: `"${currentMonth.version}"`,
      });
      return;
    }

    if (
      url.pathname === `/api/v1/certification/months/${ids.month}/summaries` &&
      method === "POST"
    ) {
      currentMonth = {
        ...currentMonth,
        version: currentMonth.version + 1,
        summary: {
          ...currentMonth.summary,
          version: currentMonth.summary.version + 1,
          decision: body.decision,
          observations: body.observations ?? null,
          terminalItemCount: currentMonth.deliverables.filter(
            (item) => item.certification?.terminal,
          ).length,
          totalItemCount: currentMonth.deliverables.length,
        },
      };
      await fulfillJson(route, currentMonth, 201, {
        etag: `"${currentMonth.version}"`,
      });
      return;
    }

    if (
      url.pathname === `/api/v1/certification/months/${ids.month}/confirmation-requests` &&
      method === "POST"
    ) {
      currentRequest = requestView("active");
      await fulfillJson(route, currentRequest, 201, {
        etag: `"${currentRequest.version}"`,
      });
      return;
    }

    if (
      url.pathname === `/api/v1/certification/confirmation-requests/${ids.request}/actions` &&
      method === "POST"
    ) {
      const idempotencyKey = request.headers()["idempotency-key"] ?? "";
      if (committedLostAction) {
        if (idempotencyKey !== committedLostAction.idempotencyKey) {
          await fulfillJson(
            route,
            {
              code: "DUPLICATE_BUSINESS_INTENT",
              message: "The retry did not retain the committed user-intent key.",
              correlationId: "f0400000-0000-0000-0000-000000000098",
            },
            409,
            { "x-correlation-id": "f0400000-0000-0000-0000-000000000098" },
          );
          return;
        }
        currentRequest = committedLostAction.request;
        await fulfillJson(route, currentRequest, 200, {
          etag: `"${currentRequest.version}"`,
        });
        return;
      }
      const decision = body.decision === "REQUEST_CORRECTION" ? "REQUEST_CORRECTION" : "CONFIRM";
      const actionResult = {
        ...currentRequest,
        version: currentRequest.version + 1,
        state: decision === "CONFIRM" ? "CONFIRMED" : "CHANGES_REQUESTED",
        locked: true,
        actions: [actionView(decision)],
      };
      actionBusinessEffects += 1;
      if (options.loseFirstActionResponse) {
        committedLostAction = {
          idempotencyKey,
          request: actionResult,
        };
        await route.abort("connectionreset");
        return;
      }
      currentRequest = actionResult;
      await fulfillJson(route, currentRequest, 200, {
        etag: `"${currentRequest.version}"`,
      });
      return;
    }

    const evidenceReviewMatch = url.pathname.match(
      /^\/api\/v1\/certification\/(inbound-messages|manual-evidence)\/([^/]+)\/reviews$/,
    );
    if (evidenceReviewMatch && method === "POST") {
      const [, resource, encodedReviewId] = evidenceReviewMatch;
      const reviewId = decodeURIComponent(encodedReviewId ?? "");
      const review = currentMonth.inboundReviews.find((candidate) => candidate.id === reviewId);
      const expectedKind = resource === "inbound-messages" ? "INBOUND_MESSAGE" : "MANUAL_EVIDENCE";
      if (!review || review.reviewKind !== expectedKind) {
        await fulfillJson(
          route,
          {
            code: "NOT_FOUND",
            message: "The requested record is unavailable.",
          },
          404,
        );
        return;
      }

      const replayKey = `${resource}:${reviewId}`;
      const idempotencyKey = request.headers()["idempotency-key"] ?? "";
      const bodyFingerprint = JSON.stringify(body);
      const committed = committedEvidenceReviews.get(replayKey);
      if (committed?.idempotencyKey === idempotencyKey) {
        if (committed.body !== bodyFingerprint) {
          await fulfillJson(
            route,
            {
              code: "IDEMPOTENCY_KEY_REUSED",
              message: "The idempotency key was already used with a different review body.",
            },
            409,
          );
          return;
        }
        await fulfillJson(route, committed.result, 201, {
          etag: `"${committed.result.version}"`,
        });
        return;
      }

      const allowedDecisions =
        review.reviewKind === "INBOUND_MESSAGE"
          ? ["ACCEPT_INTERPRETATION", "REJECT_INTERPRETATION", "QUARANTINE"]
          : ["APPROVE", "REJECT"];
      if (
        !allowedDecisions.includes(String(body.decision)) ||
        typeof body.reasoning !== "string" ||
        !body.reasoning.trim()
      ) {
        await fulfillJson(
          route,
          {
            code: "VALIDATION_FAILED",
            message: "A permitted decision and reviewer reasoning are required.",
          },
          400,
        );
        return;
      }
      if (body.expectedReviewVersion !== review.version || review.reviewStatus !== "PENDING") {
        await fulfillJson(
          route,
          {
            code: "VERSION_CONFLICT",
            message: "The review is no longer at the expected pending version.",
          },
          409,
          { etag: `"${review.version}"` },
        );
        return;
      }

      const updatedReview = {
        ...review,
        version: review.version + 1,
        reviewStatus:
          body.decision === "QUARANTINE"
            ? "QUARANTINED"
            : body.decision === "REJECT_INTERPRETATION" || body.decision === "REJECT"
              ? "REJECTED"
              : "APPROVED",
        agingStatus: "RESOLVED",
        reason: body.reasoning,
      };
      currentMonth = {
        ...currentMonth,
        inboundReviews: currentMonth.inboundReviews.map((candidate) =>
          candidate.id === updatedReview.id ? updatedReview : candidate,
        ),
      };
      committedEvidenceReviews.set(replayKey, {
        idempotencyKey,
        body: bodyFingerprint,
        result: updatedReview,
      });
      evidenceReviewBusinessEffects += 1;
      await fulfillJson(route, updatedReview, 201, {
        etag: `"${updatedReview.version}"`,
      });
      return;
    }

    if (
      url.pathname === `/api/v1/certification/months/${ids.month}/reopen-requests` &&
      method === "POST"
    ) {
      currentMonth = {
        ...currentMonth,
        version: currentMonth.version + 1,
        lifecycleState: "REOPEN_REQUESTED",
        stale: true,
        confirmationHistory: [
          ...currentMonth.confirmationHistory,
          {
            id: ids.requestV3,
            version: 3,
            state: "DRAFT",
            dueAt: "2026-09-02T18:30:00+05:30",
            createdAt: "2026-08-30T11:00:00Z",
            supersedesRequestId: ids.request,
          },
        ],
        timeline: [
          ...currentMonth.timeline,
          {
            id: ids.timelineReopen,
            label: "Governed reopen requested",
            state: "REOPEN_REQUESTED",
            actorDisplay: "Reliance Governance",
            recordedAt: "2026-08-30T11:00:00Z",
            representedAt: null,
            correlationId: ids.reopenCorrelation,
          },
        ],
      };
      currentReadiness = {
        ...currentReadiness,
        version: currentReadiness.version + 1,
        status: "STALE",
        stale: true,
        f05HandoffStatus: "INVALIDATED",
      };
      await fulfillJson(route, currentMonth, 201, {
        etag: `"${currentMonth.version}"`,
      });
      return;
    }

    await fulfillJson(
      route,
      {
        code: "E2E_FIXTURE_MISSING",
        message: `No certification browser fixture for ${method} ${url.pathname}`,
      },
      404,
    );
  });

  return {
    ids,
    requests,
    mutations: requests.filter((request) => request.method !== "GET"),
    get actionBusinessEffects() {
      return actionBusinessEffects;
    },
    get evidenceReviewBusinessEffects() {
      return evidenceReviewBusinessEffects;
    },
  };
}

export const certificationFixture = {
  ids,
  evidence: safeEvidence,
};
