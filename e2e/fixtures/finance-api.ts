import type { Page, Route } from "@playwright/test";

export type FinanceApiOptions = {
  readOnly?: boolean;
  stale?: boolean;
  quarantined?: boolean;
  restrictPayment?: boolean;
  integrityFailed?: boolean;
  withoutDocument?: boolean;
  blockedExceptionRule?: boolean;
};

export type FinanceRecordedRequest = {
  method: string;
  path: string;
  search: string;
  headers: Record<string, string>;
  body: unknown;
};

const ids = {
  month: "30000000-0000-0000-0000-000000000501",
  secondMonth: "30000000-0000-0000-0000-000000000502",
  invoice: "50000000-0000-0000-0000-000000000501",
  package: "60000000-0000-0000-0000-000000000501",
  priorPackage: "60000000-0000-0000-0000-000000000500",
  artifact: "70000000-0000-0000-0000-000000000501",
  share: "80000000-0000-0000-0000-000000000501",
  query: "90000000-0000-0000-0000-000000000501",
  export: "a0000000-0000-0000-0000-000000000501",
  rule: "b0000000-0000-0000-0000-000000000501",
  readiness: "c0000000-0000-0000-0000-000000000501",
  exception: "d0000000-0000-0000-0000-000000000501",
  policy: "e0000000-0000-0000-0000-000000000501",
};

const now = "2026-07-27T09:30:00Z";
const hash = "sha256:954437d18b01f86bde5cf4ca897972bad61db6846a6692b6cd61b841522ea7b3";

const allPermissions = [
  "catalog.read",
  "finance.read",
  "EVIDENCE_PACKAGE_VIEW",
  "EVIDENCE_PACKAGE_GENERATE",
  "EVIDENCE_PACKAGE_DOWNLOAD",
  "EVIDENCE_PACKAGE_ACCESS_AUDIT",
  "INVOICE_VIEW",
  "INVOICE_CREATE",
  "INVOICE_UPLOAD",
  "INVOICE_REPLACE",
  "INVOICE_SUBMIT",
  "PROCUREMENT_REVIEW",
  "PROCUREMENT_QUERY",
  "PROCUREMENT_EXCEPTION",
  "PAYMENT_VIEW",
  "PAYMENT_UPDATE",
  "REPORT_VIEW",
  "REPORT_EXPORT",
];

function source(overrides: Record<string, unknown> = {}) {
  return {
    sourceType: "F04_CONFIRMATION",
    sourceId: "confirmation-august",
    version: "7",
    checksum: hash,
    provenance: "Attributable product-owner confirmation",
    freshness: "CURRENT",
    temporalMode: "SNAPSHOT",
    representedAt: "2026-07-26T12:00:00Z",
    recordedAt: "2026-07-26T12:01:00Z",
    superseded: false,
    ...overrides,
  };
}

function permissions(options: FinanceApiOptions) {
  return options.restrictPayment
    ? allPermissions.filter((permission) => !permission.startsWith("PAYMENT_"))
    : allPermissions;
}

function pageOf<T>(items: T[], nextCursor: string | null = null) {
  return { items, nextCursor, totalCount: items.length };
}

function packageSummary(packageId = ids.package, version = 2) {
  return {
    packageId,
    monthId: ids.month,
    version,
    state: "AVAILABLE",
    progressPercent: 100,
    canonicalInputHash: hash,
    policyVersion: "f05-readiness-v1",
    templateVersion: "evidence-package-v1",
    generatedAt: now,
    supersedesPackageId: version === 2 ? ids.priorPackage : null,
    current: version === 2,
    permissions: allPermissions,
  };
}

function invoiceView(options: FinanceApiOptions, version = 4) {
  const scanStatus = options.withoutDocument
    ? null
    : options.quarantined ? "QUARANTINED" : "PASSED";
  return {
    invoiceId: ids.invoice,
    monthId: ids.month,
    monthLabel: "July 2026",
    engagementLabel: "ArrowFoundry × Reliance",
    vendorLabel: "ArrowFoundry",
    invoiceNumber: "AF-2026-071",
    state: "PROCUREMENT_REVIEW",
    scanStatus,
    version,
    updatedAt: now,
    freshness: options.stale ? "STALE" : "CURRENT",
    permissions: permissions(options),
    etag: `"${version}"`,
    readOnly: options.readOnly ?? false,
    uploadPolicy: {
      policyVersion: "f05-policy-v1",
      allowedMimeTypes: ["application/pdf", "image/jpeg", "image/png"],
      maximumUploadBytes: 20_000_000,
      allowedClassifications: ["CONFIDENTIAL"],
      retentionPolicy: "FINANCE_EVIDENCE",
    },
    representedMetadata: {
      invoiceNumber: "AF-2026-071",
      invoiceDate: "2026-07-25",
      billingPeriodStart: "2026-07-01",
      billingPeriodEnd: "2026-07-31",
      currency: "INR",
      taxableValue: "250000.00",
      taxValue: "45000.00",
      totalValue: "295000.00",
      purchaseOrderReference: "PO-REL-2026-07",
      workOrderReference: "WO-4421",
    },
    currentDocument: options.withoutDocument
      ? null
      : {
          documentId: "document-current",
          fileName: "invoice-july-2026.pdf",
          mimeType: "application/pdf",
          sizeBytes: 82400,
          sha256: hash,
          objectVersion: `invoice-object-v${version}`,
          scanStatus,
          classification: "CONFIDENTIAL",
          retentionPolicy: "INVOICE_STANDARD",
          uploadedAt: now,
          superseded: false,
        },
    versions: [
      {
        versionId: "invoice-version-current",
        version,
        kind: "PRIMARY",
        state: "PROCUREMENT_REVIEW",
        createdAt: now,
        createdByDisplay: "Vendor Finance Owner",
        reason: "Current exact invoice evidence",
        supersedesVersionId: null,
        document: null,
      },
    ],
    readiness: {
      runId: ids.readiness,
      version: 3,
      inputHash: hash,
      policyVersion: "f05-readiness-v1",
      evaluatedAt: now,
      eligibleForSubmission: true,
      stale: false,
      rules: [
        {
          ruleId: "F04_CONFIRMATION",
          pillar: "Confirmation",
          label: "Exact F04 confirmation is current",
          mandatory: true,
          status: "PASS",
          severity: "INFO",
          ownerDisplay: "Product Owner",
          remediationLabel: null,
          remediationPath: null,
          source: source(),
          exceptionId: null,
          exceptionExpiresAt: null,
        },
        ...(options.blockedExceptionRule
          ? [
              {
                ruleId: ids.rule,
                pillar: "Invoice",
                label: "Purchase order match requires exception",
                mandatory: true,
                status: "BLOCKED_INVALID_VERSION",
                severity: "BLOCKING",
                ownerDisplay: "Vendor Finance Owner",
                remediationLabel: "Correct source evidence",
                remediationPath: `/finance?monthId=${ids.month}&invoiceId=${ids.invoice}`,
                source: source({
                  sourceType: "INVOICE",
                  sourceId: ids.invoice,
                  version: String(version),
                }),
                exceptionId: null,
                exceptionExpiresAt: null,
              },
            ]
          : []),
      ],
    },
    linkedPackage: packageSummary(),
    reviews: [],
    queries: [],
    exceptions: [],
    paymentTimeline: options.restrictPayment
      ? []
      : [
          {
            paymentEventId: "payment-event-1",
            version: 1,
            status: "SUBMITTED_TO_AP",
            source: "AP",
            provenance: "Append-only AP status",
            comment: "Received by AP",
            externalReference: "AP-9921",
            statusAt: now,
            expectedPaymentDate: "2026-08-10",
            actualPaymentDate: null,
            recordedAt: now,
            recordedByDisplay: "AP Operator",
          },
        ],
  };
}

function packageView(options: FinanceApiOptions) {
  return {
    ...packageSummary(),
    engagementLabel: "ArrowFoundry × Reliance",
    monthLabel: "July 2026",
    provenanceDisclosure:
      "Package contains exact versioned sources. Procurement exceptions remain disclosed.",
    integrityVerified: !options.integrityFailed,
    sources: [source()],
    manifestItems: [
      {
        itemId: "manifest-invoice",
        logicalType: "INVOICE_DOCUMENT",
        safeName: "invoice-july-2026.pdf",
        source: source({
          sourceType: "INVOICE",
          sourceId: ids.invoice,
          version: "4",
          provenance: "Private versioned evidence storage",
        }),
        mimeType: "application/pdf",
        sizeBytes: 82400,
        sha256: hash,
        objectVersion: "invoice-object-v4",
        classification: "CONFIDENTIAL",
        retentionPolicy: "INVOICE_STANDARD",
      },
    ],
    artifacts: [
      {
        artifactId: ids.artifact,
        label: "Evidence package",
        format: "ZIP",
        sha256: hash,
        sizeBytes: 122400,
        scanStatus: options.quarantined ? "QUARANTINED" : "PASSED",
        classification: "CONFIDENTIAL",
        downloadAllowed: !options.quarantined && !options.integrityFailed,
      },
    ],
  };
}

function monthSummary(monthId = ids.month, monthLabel = "July 2026") {
  return {
    monthId,
    version: 8,
    monthLabel,
    engagementLabel: "ArrowFoundry × Reliance",
    vendorLabel: "ArrowFoundry",
    readiness: "COMPLETE",
    invoiceCount: 1,
    currentPackageVersion: 2,
    refreshedAt: now,
    freshness: "CURRENT",
    permissions: allPermissions,
  };
}

function invoiceSummary(options: FinanceApiOptions) {
  const invoice = invoiceView(options);
  return {
    invoiceId: invoice.invoiceId,
    monthId: invoice.monthId,
    monthLabel: invoice.monthLabel,
    engagementLabel: invoice.engagementLabel,
    vendorLabel: invoice.vendorLabel,
    invoiceNumber: invoice.invoiceNumber,
    state: invoice.state,
    scanStatus: invoice.scanStatus,
    version: invoice.version,
    updatedAt: invoice.updatedAt,
    freshness: invoice.freshness,
    permissions: invoice.permissions,
  };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    headers: { "x-correlation-id": "f05-e2e-correlation" },
    body: JSON.stringify(body),
  });
}

function requestBody(route: Route) {
  const request = route.request();
  if (!request.postData()) return null;
  if ((request.headers()["content-type"] ?? "").includes("application/json")) {
    return request.postDataJSON();
  }
  return { multipart: true, byteLength: request.postDataBuffer()?.byteLength ?? 0 };
}

export async function mockFinanceApi(page: Page, options: FinanceApiOptions = {}) {
  const requests: FinanceRecordedRequest[] = [];
  let invoice = invoiceView(options);
  let actor = {
    subject: "procurement.requester@reliance.example",
    displayName: "Procurement Requester",
  };
  let exceptionRequesterSubject: string | null = null;
  let shares = [
    {
      shareId: ids.share,
      packageId: ids.package,
      recipientSubject: "auditor@reliance.example",
      accessScope: "VIEW",
      expiresAt: "2026-08-31T18:30:00Z",
      revoked: false,
      revokedAt: null as string | null,
      createdByDisplay: "Finance Owner",
      createdAt: now,
      correlationId: "f05-share-correlation",
    },
  ];
  let exportJobs: Array<Record<string, unknown>> = [
    {
      exportId: ids.export,
      reportId: "INVOICE_READINESS",
      reportVersion: "v1",
      format: "CSV",
      status: "READY",
      progressPercent: 100,
      completedAt: now,
      expiresAt: "2026-07-28T09:30:00Z",
      rowCount: 12,
      resultHash: hash,
      sourceFreshnessAt: now,
      temporalMode: "CURRENT",
      filters: "{}",
      downloadAllowed: true,
      correlationId: "f05-export-correlation",
    },
  ];

  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    if (path !== "/api/v1/me" && !path.startsWith("/api/v1/finance/")) {
      await route.fallback();
      return;
    }
    const body = requestBody(route);
    requests.push({
      method,
      path,
      search: url.search,
      headers: request.headers(),
      body,
    });

    if (path === "/api/v1/me") {
      await json(route, {
        id: actor.subject,
        subject: actor.subject,
        email: actor.subject,
        displayName: actor.displayName,
        memberships: [
          {
            organizationId: "org-reliance",
            organizationCode: "RELIANCE",
            organizationName: "Reliance Intelligence",
            roleCode: "FINANCE_AP",
            validFrom: "2026-01-01",
            validTo: null,
          },
        ],
        organizationIds: ["org-reliance"],
        permissions: permissions(options),
      });
      return;
    }
    if (path === "/api/v1/finance/access") {
      await json(route, {
        permissions: permissions(options),
        organizationLabel: "Reliance",
        scopeLabel: "ArrowFoundry engagement",
        storage: "CONFIGURED",
        scanner: "CONFIGURED",
        renderer: "CONFIGURED",
        erp: "ACTION_REQUIRED",
      });
      return;
    }
    if (path === "/api/v1/finance/months" && method === "GET") {
      const second = url.searchParams.has("cursor");
      await json(
        route,
        pageOf(
          [second ? monthSummary(ids.secondMonth, "June 2026") : monthSummary()],
          second ? null : "second-month-page",
        ),
      );
      return;
    }
    if (path === `/api/v1/finance/months/${ids.month}`) {
      await json(route, {
        month: monthSummary(),
        permissions: permissions(options),
        sourceHandoff: {
          contractVersion: "f04-f05-v1",
          confirmationDisposition: "CONFIRMED",
          source: source(),
        },
        invoices: [invoiceSummary(options)],
        packages: [packageSummary()],
        currentReadinessRunId: ids.readiness,
        blockers: [],
      });
      return;
    }
    if (path === "/api/v1/finance/invoices" && method === "GET") {
      const second = url.searchParams.has("cursor");
      await json(route, pageOf(second ? [] : [invoiceSummary(options)], second ? null : "second-invoice-page"));
      return;
    }
    if (path === `/api/v1/finance/invoices/${ids.invoice}` && method === "GET") {
      await json(route, invoice);
      return;
    }
    if (
      (path === `/api/v1/finance/invoices/${ids.invoice}/documents` ||
        path === `/api/v1/finance/invoices/${ids.invoice}/documents/replace`) &&
      method === "POST"
    ) {
      invoice = {
        ...invoice,
        version: invoice.version + 1,
        etag: `"${invoice.version + 1}"`,
        updatedAt: now,
        currentDocument: {
          documentId: "document-replacement",
          fileName: "replacement-invoice.pdf",
          mimeType: "application/pdf",
          sizeBytes: 82400,
          sha256: hash,
          scanStatus: "PASSED",
          objectVersion: `invoice-object-v${invoice.version + 1}`,
          classification: "CONFIDENTIAL",
          retentionPolicy: "INVOICE_STANDARD",
          uploadedAt: now,
          superseded: false,
        },
        scanStatus: "PASSED",
      };
      await json(route, invoice);
      return;
    }
    if (
      path === `/api/v1/finance/invoices/${ids.invoice}/readiness-runs` &&
      method === "POST"
    ) {
      invoice = {
        ...invoice,
        readiness: {
          ...invoice.readiness!,
          runId: `readiness-run-v${invoice.version}`,
          evaluatedAt: now,
          stale: false,
        },
      };
      await json(route, invoice);
      return;
    }
    if (path === `/api/v1/finance/invoices/${ids.invoice}/submit` && method === "POST") {
      invoice = { ...invoice, state: "SUBMITTED_TO_PROCUREMENT", readOnly: true };
      await json(route, invoice);
      return;
    }
    if (path === `/api/v1/finance/months/${ids.month}/packages`) {
      if (method === "POST") {
        await json(route, packageSummary(), 201);
      } else {
        const second = url.searchParams.has("cursor");
        await json(
          route,
          pageOf(
            second ? [packageSummary(ids.priorPackage, 1)] : [packageSummary()],
            second ? null : "prior-package-page",
          ),
        );
      }
      return;
    }
    if (path === `/api/v1/finance/packages/${ids.package}`) {
      await json(route, packageView(options));
      return;
    }
    if (path === `/api/v1/finance/packages/${ids.package}/diff`) {
      await json(route, {
        fromPackageId: ids.priorPackage,
        toPackageId: ids.package,
        fromVersion: 1,
        toVersion: 2,
        added: [{ logicalType: "INVOICE_DOCUMENT", sourceId: ids.invoice, version: "4" }],
        changed: [],
        removed: [],
      });
      return;
    }
    if (path === `/api/v1/finance/packages/${ids.package}/access-events`) {
      await json(
        route,
        pageOf([
          {
            accessId: "access-1",
            action: "VIEWED",
            actorDisplay: "Finance Owner",
            authorityDisplay: "Engagement finance authority",
            recordedAt: now,
            expiresAt: null,
            revokedAt: null,
            correlationId: "f05-access-correlation",
          },
        ]),
      );
      return;
    }
    if (path === `/api/v1/finance/packages/${ids.package}/shares` && method === "GET") {
      await json(route, pageOf(shares));
      return;
    }
    if (path === `/api/v1/finance/packages/${ids.package}/shares` && method === "POST") {
      const input = body as Record<string, unknown>;
      const created = {
        shareId: "80000000-0000-0000-0000-000000000502",
        packageId: ids.package,
        recipientSubject: String(input.recipientSubject),
        accessScope: String(input.accessScope),
        expiresAt: String(input.expiresAt),
        revoked: false,
        revokedAt: null,
        createdByDisplay: "Finance Owner",
        createdAt: now,
        correlationId: "f05-created-share-correlation",
      };
      shares = [created, ...shares];
      await json(route, created, 201);
      return;
    }
    if (
      path.startsWith(`/api/v1/finance/packages/${ids.package}/shares/`) &&
      path.endsWith("/revoke") &&
      method === "POST"
    ) {
      const shareId = path.split("/").at(-2);
      shares = shares.map((share) =>
        share.shareId === shareId ? { ...share, revoked: true, revokedAt: now } : share,
      );
      await json(route, shares.find((share) => share.shareId === shareId));
      return;
    }
    if (path === "/api/v1/finance/procurement/control-tower") {
      const second = url.searchParams.has("cursor");
      await json(route, {
        permissions: permissions(options),
        refreshedAt: now,
        freshness: options.stale ? "STALE" : "CURRENT",
        rows: pageOf(
          second
            ? []
            : [
                {
                  monthId: ids.month,
                  monthLabel: "July 2026",
                  engagementLabel: "ArrowFoundry × Reliance",
                  invoiceId: ids.invoice,
                  invoiceNumber: invoice.invoiceNumber,
                  invoiceState: invoice.state,
                  packageState: "CURRENT",
                  packageVersion: 2,
                  paymentStatus: options.restrictPayment ? null : "SUBMITTED_TO_AP",
                  freshness: options.stale ? "STALE" : "CURRENT",
                  ownerDisplay: "Finance Owner",
                },
              ],
          second ? null : "second-control-tower-page",
        ),
      });
      return;
    }
    if (
      path === `/api/v1/finance/procurement/invoices/${ids.invoice}/reviews` &&
      method === "POST"
    ) {
      const input = body as Record<string, unknown>;
      invoice = {
        ...invoice,
        reviews: [
          ...invoice.reviews,
          {
            reviewId: "review-1",
            version: 1,
            decision: input.decision,
            category: input.category,
            comment: input.comment,
            actorDisplay: "Procurement Reviewer",
            authorityDisplay: "Reliance Procurement",
            invoiceVersion: invoice.version,
            packageVersion: 2,
            readinessRunId: ids.readiness,
            recordedAt: now,
          },
        ],
      };
      await json(route, invoice);
      return;
    }
    if (
      path === `/api/v1/finance/procurement/invoices/${ids.invoice}/queries` &&
      method === "POST"
    ) {
      const input = body as Record<string, unknown>;
      invoice = {
        ...invoice,
        queries: [
          ...invoice.queries,
          {
            queryId: ids.query,
            version: 1,
            status: "OPEN",
            category: input.category,
            summary: input.summary,
            ownerDisplay: input.ownerId,
            dueAt: input.dueAt,
            createdAt: now,
            sourceCorrectionPath: `/finance?invoiceId=${ids.invoice}`,
          },
        ],
      };
      await json(route, invoice);
      return;
    }
    if (
      path === `/api/v1/finance/procurement/invoices/${ids.invoice}/exceptions` &&
      method === "POST"
    ) {
      const input = body as Record<string, unknown>;
      if ("secondApproverId" in input) {
        await json(
          route,
          {
            code: "INVALID_EXCEPTION_REQUEST",
            detail: "An exception request cannot nominate its approver.",
          },
          400,
        );
        return;
      }
      exceptionRequesterSubject = actor.subject;
      const nextVersion = invoice.version + 1;
      invoice = {
        ...invoice,
        state: "EVIDENCE_PENDING",
        version: nextVersion,
        etag: `"${nextVersion}"`,
        exceptions: [
          ...invoice.exceptions,
          {
            exceptionId: ids.exception,
            ruleId: String(input.ruleId),
            status: "PENDING_SECOND_APPROVAL",
            rationale: String(input.rationale),
            authorityDisplay: "Server-derived Procurement exception authority",
            secondApproverRequired: true,
            requestedByDisplay: actor.displayName,
            secondApproverDisplay: null,
            validUntil: String(input.validUntil),
            invoiceVersion: invoice.version,
            readinessRunId: String(input.readinessRunId),
            packageId: String(input.packageId),
            packageVersion: Number(input.packageVersion),
            policyVersionId: ids.policy,
            policyVersion: 2,
            createdAt: now,
            secondApprovedAt: null,
            expiredAt: null,
          },
        ],
      };
      await json(route, {
        exceptionId: ids.exception,
        invoiceId: ids.invoice,
        exceptionStatus: "PENDING_SECOND_APPROVAL",
        ruleId: input.ruleId,
        requestedReadinessRunId: input.readinessRunId,
        packageId: input.packageId,
        packageVersion: input.packageVersion,
        policyVersionId: ids.policy,
        policyVersion: 2,
        validUntil: input.validUntil,
        requestedBySubject: actor.subject,
        secondApproverSubject: null,
        acceptedReadinessRunId: null,
        state: invoice.state,
        version: invoice.version,
        etag: invoice.version,
        requestedAt: now,
        secondApprovedAt: null,
        expiredAt: null,
      });
      return;
    }
    if (
      path ===
        `/api/v1/finance/procurement/exceptions/${ids.exception}/second-approval` &&
      method === "POST"
    ) {
      const input = body as Record<string, unknown>;
      if (actor.subject === exceptionRequesterSubject) {
        await json(
          route,
          {
            code: "SEPARATION_OF_DUTIES_VIOLATION",
            detail: "The exception requester cannot provide its second approval.",
          },
          409,
        );
        return;
      }
      const pending = invoice.exceptions.find(
        (candidate) => candidate.exceptionId === ids.exception,
      );
      const exactBinding =
        input.invoiceId === ids.invoice &&
        input.ruleId === pending?.ruleId &&
        input.readinessRunId === pending?.readinessRunId &&
        input.packageId === pending?.packageId &&
        input.packageVersion === pending?.packageVersion &&
        input.policyVersionId === pending?.policyVersionId &&
        input.policyVersion === pending?.policyVersion;
      if (!exactBinding) {
        await json(
          route,
          {
            code: "EXCEPTION_APPROVAL_BINDING_MISMATCH",
            detail: "The approval does not match the exact pending exception.",
          },
          409,
        );
        return;
      }
      const nextVersion = invoice.version + 1;
      invoice = {
        ...invoice,
        state: "EXCEPTION_ACCEPTED",
        version: nextVersion,
        etag: `"${nextVersion}"`,
        exceptions: invoice.exceptions.map((candidate) =>
          candidate.exceptionId === ids.exception
            ? {
                ...candidate,
                status: "ACCEPTED",
                secondApproverDisplay: actor.displayName,
                secondApprovedAt: now,
              }
            : candidate,
        ),
      };
      await json(route, {
        exceptionId: ids.exception,
        invoiceId: ids.invoice,
        exceptionStatus: "ACCEPTED",
        ruleId: input.ruleId,
        requestedReadinessRunId: input.readinessRunId,
        packageId: input.packageId,
        packageVersion: input.packageVersion,
        policyVersionId: input.policyVersionId,
        policyVersion: input.policyVersion,
        validUntil: pending?.validUntil,
        requestedBySubject: exceptionRequesterSubject,
        secondApproverSubject: actor.subject,
        acceptedReadinessRunId: "c0000000-0000-0000-0000-000000000502",
        state: invoice.state,
        version: invoice.version,
        etag: invoice.version,
        requestedAt: pending?.createdAt,
        secondApprovedAt: now,
        expiredAt: null,
      });
      return;
    }
    if (path === `/api/v1/finance/invoices/${ids.invoice}/payments` && method === "POST") {
      const input = body as Record<string, unknown>;
      invoice = {
        ...invoice,
        paymentTimeline: [
          ...invoice.paymentTimeline,
          {
            paymentEventId: "payment-event-2",
            version: 2,
            status: String(input.status),
            source: "MANUAL",
            provenance: "Authorized manual AP update",
            comment: String(input.comment),
            externalReference:
              input.externalReference == null ? null : String(input.externalReference),
            statusAt: String(input.statusAt),
            expectedPaymentDate:
              input.expectedPaymentDate == null ? null : String(input.expectedPaymentDate),
            actualPaymentDate:
              input.actualPaymentDate == null ? null : String(input.actualPaymentDate),
            recordedAt: now,
            recordedByDisplay: "AP Operator",
          },
        ],
      };
      await json(route, invoice);
      return;
    }
    if (path === "/api/v1/finance/dashboard") {
      await json(route, {
        personaLabel: "Finance and Procurement",
        refreshedAt: now,
        freshness: options.stale ? "STALE" : "CURRENT",
        permissions: permissions(options),
        metrics: [
          {
            metricCode: "INVOICES_IN_SCOPE",
            displayName: "Invoices in scope",
            value: 1,
            version: 1,
            availability: "AVAILABLE",
            freshness: options.stale ? "STALE" : "CURRENT",
            sourceLabel: "F05 invoice ledger",
          },
        ],
        queues: [
          {
            code: "PROCUREMENT_REVIEW",
            label: "Procurement review required",
            count: 1,
            path: "/finance/procurement",
          },
        ],
      });
      return;
    }
    if (path === "/api/v1/finance/reports" && method === "GET") {
      await json(route, {
        permissions: permissions(options),
        definitions: [
          {
            reportId: "INVOICE_READINESS",
            version: "v1",
            label: "Invoice readiness",
            formats: ["CSV", "JSON"],
            temporalModes: ["CURRENT", "SNAPSHOT"],
          },
        ],
        exports: pageOf(exportJobs, url.searchParams.has("cursor") ? null : "second-export-page"),
      });
      return;
    }
    if (path === "/api/v1/finance/exports" && method === "POST") {
      const input = body as Record<string, unknown>;
      const created = {
        exportId: "a0000000-0000-0000-0000-000000000502",
        reportId: String(input.reportId),
        reportVersion: String(input.reportVersion),
        format: String(input.format),
        status: "PENDING",
        progressPercent: 0,
        completedAt: null,
        expiresAt: null,
        rowCount: null,
        resultHash: null,
        sourceFreshnessAt: now,
        temporalMode: String(input.temporalMode),
        filters: JSON.stringify(input.filters),
        downloadAllowed: false,
        correlationId: "f05-created-export-correlation",
      };
      exportJobs = [created, ...exportJobs];
      await json(route, created, 201);
      return;
    }

    await json(route, { message: `No finance E2E fixture for ${method} ${path}` }, 404);
  });

  return {
    requests,
    actAsDistinctProcurementApprover() {
      actor = {
        subject: "procurement.approver@reliance.example",
        displayName: "Distinct Procurement Approver",
      };
    },
  };
}

export const financeFixture = { ids, hash };
