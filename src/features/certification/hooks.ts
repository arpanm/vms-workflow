import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";

import { certificationApi } from "./api";
import type {
  CertificationRequest,
  ClarificationRequest,
  ConfirmationActionRequest,
  ConfirmationRequestInput,
  InboundMessageReviewRequest,
  ManualEvidenceReviewRequest,
  MonthCertificationView,
  ReopenRequestInput,
  SaveSubmissionRequest,
  SummaryRequest,
} from "./contracts";
import { MutationIntentStore } from "./idempotency";

export const certificationKeys = {
  month: (monthId: string) => ["certification", "month", monthId] as const,
  readiness: (monthId: string) => ["certification", "readiness", monthId] as const,
  confirmation: (requestId: string) => ["certification", "confirmation", requestId] as const,
};

export function useCertificationMonth(monthId: string) {
  return useQuery({
    queryKey: certificationKeys.month(monthId),
    queryFn: () => certificationApi.month(monthId),
    enabled: Boolean(monthId),
  });
}

export function useReadiness(monthId: string) {
  return useQuery({
    queryKey: certificationKeys.readiness(monthId),
    queryFn: () => certificationApi.readiness(monthId),
    enabled: Boolean(monthId),
  });
}

export function useConfirmationRequest(requestId: string) {
  return useQuery({
    queryKey: certificationKeys.confirmation(requestId),
    queryFn: () => certificationApi.confirmationRequest(requestId),
    enabled: Boolean(requestId),
  });
}

function useRetainedIntentMutation<TInput, TResult>({
  mutationFn,
  onSuccess,
  onError,
}: {
  mutationFn: (input: TInput, idempotencyKey: string) => Promise<TResult>;
  onSuccess?: (result: TResult) => unknown;
  onError?: (error: Error) => unknown;
}) {
  const retainedIntent = useRef(new MutationIntentStore<TInput>());

  return useMutation({
    mutationFn: (input: TInput) => {
      const intent = retainedIntent.current.acquire(input);
      return mutationFn(input, intent.idempotencyKey);
    },
    onSuccess: (result) => {
      retainedIntent.current.settle();
      return onSuccess?.(result);
    },
    onError: (error) => {
      retainedIntent.current.settle(error);
      return onError?.(error);
    },
  });
}

function useMonthMutation<TInput>(
  monthId: string,
  mutationFn: (
    input: TInput,
    idempotencyKey: string,
  ) => ReturnType<typeof certificationApi.saveSubmission>,
) {
  const queryClient = useQueryClient();
  return useRetainedIntentMutation({
    mutationFn,
    onSuccess: (month) => {
      queryClient.setQueryData(certificationKeys.month(monthId), month);
      return queryClient.invalidateQueries({
        queryKey: certificationKeys.readiness(monthId),
      });
    },
    onError: () =>
      queryClient.invalidateQueries({
        queryKey: certificationKeys.month(monthId),
      }),
  });
}

export function useSaveSubmission(monthId: string) {
  return useMonthMutation(monthId, (input: SaveSubmissionRequest, idempotencyKey) =>
    certificationApi.saveSubmission(monthId, input, idempotencyKey),
  );
}

export type SubmitSubmissionInput = {
  submissionId: string;
  expectedVersion: number;
};

export function useSubmitSubmission(monthId: string) {
  return useMonthMutation(
    monthId,
    ({ submissionId, expectedVersion }: SubmitSubmissionInput, idempotencyKey) =>
      certificationApi.submit(submissionId, expectedVersion, idempotencyKey),
  );
}

export function useClarification(monthId: string, submissionId: string) {
  return useMonthMutation(monthId, (input: ClarificationRequest, idempotencyKey) =>
    certificationApi.requestClarification(submissionId, input, idempotencyKey),
  );
}

export function useCertificationDecision(monthId: string, submissionId: string) {
  return useMonthMutation(monthId, (input: CertificationRequest, idempotencyKey) =>
    certificationApi.certify(submissionId, input, idempotencyKey),
  );
}

export function useCreateSummary(monthId: string) {
  return useMonthMutation(monthId, (input: SummaryRequest, idempotencyKey) =>
    certificationApi.createSummary(monthId, input, idempotencyKey),
  );
}

export function useRequestConfirmation(monthId: string) {
  const queryClient = useQueryClient();
  return useRetainedIntentMutation({
    mutationFn: (input: ConfirmationRequestInput, idempotencyKey) =>
      certificationApi.requestConfirmation(monthId, input, idempotencyKey),
    onSuccess: (request) => {
      queryClient.setQueryData(certificationKeys.confirmation(request.id), request);
      void queryClient.invalidateQueries({ queryKey: certificationKeys.month(monthId) });
      return queryClient.invalidateQueries({
        queryKey: certificationKeys.readiness(monthId),
      });
    },
  });
}

export function useConfirmationAction(requestId: string) {
  const queryClient = useQueryClient();
  return useRetainedIntentMutation({
    mutationFn: (input: ConfirmationActionRequest, idempotencyKey) =>
      certificationApi.actOnConfirmation(requestId, input, idempotencyKey),
    onSuccess: (request) => {
      queryClient.setQueryData(certificationKeys.confirmation(requestId), request);
      void queryClient.invalidateQueries({
        queryKey: certificationKeys.month(request.monthId),
      });
      return queryClient.invalidateQueries({
        queryKey: certificationKeys.readiness(request.monthId),
      });
    },
    onError: () =>
      queryClient.invalidateQueries({
        queryKey: certificationKeys.confirmation(requestId),
      }),
  });
}

export type EvidenceReviewMutationInput =
  | {
      reviewKind: "INBOUND_MESSAGE";
      reviewId: string;
      request: InboundMessageReviewRequest;
    }
  | {
      reviewKind: "MANUAL_EVIDENCE";
      reviewId: string;
      request: ManualEvidenceReviewRequest;
    };

export function useEvidenceReview(monthId: string) {
  const queryClient = useQueryClient();
  return useRetainedIntentMutation({
    mutationFn: (input: EvidenceReviewMutationInput, idempotencyKey) =>
      input.reviewKind === "INBOUND_MESSAGE"
        ? certificationApi.reviewInboundMessage(input.reviewId, input.request, idempotencyKey)
        : certificationApi.reviewManualEvidence(input.reviewId, input.request, idempotencyKey),
    onSuccess: (review) => {
      queryClient.setQueryData<MonthCertificationView>(
        certificationKeys.month(monthId),
        (current) =>
          current
            ? {
                ...current,
                inboundReviews: current.inboundReviews.map((candidate) =>
                  candidate.id === review.id ? review : candidate,
                ),
              }
            : current,
      );
      void queryClient.invalidateQueries({
        queryKey: certificationKeys.month(monthId),
      });
      return queryClient.invalidateQueries({
        queryKey: certificationKeys.readiness(monthId),
      });
    },
    onError: () =>
      queryClient.invalidateQueries({
        queryKey: certificationKeys.month(monthId),
      }),
  });
}

export function useRequestReopen(monthId: string) {
  return useMonthMutation(monthId, (input: ReopenRequestInput, idempotencyKey) =>
    certificationApi.requestReopen(monthId, input, idempotencyKey),
  );
}
