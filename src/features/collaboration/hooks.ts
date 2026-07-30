import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { collaborationApi } from "./api";
import type { CreateWorkItemInput, WorkItemBucket } from "./contracts";

export const collaborationKeys = {
  all: ["collaboration"] as const,
  workItems: (
    engagementId: string,
    bucket: WorkItemBucket,
    assigned: boolean,
    mentioned: boolean,
  ) => ["collaboration", "work-items", engagementId, bucket, assigned, mentioned] as const,
};

export function useWorkItems(
  engagementId: string,
  bucket: WorkItemBucket,
  assigned: boolean,
  mentioned: boolean,
) {
  return useQuery({
    queryKey: collaborationKeys.workItems(engagementId, bucket, assigned, mentioned),
    queryFn: () =>
      collaborationApi.listWorkItems(engagementId, bucket, assigned, mentioned),
    enabled: Boolean(engagementId),
  });
}

export function useCollaborationMutation<TInput>(
  mutationFn: (input: TInput) => Promise<unknown>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: collaborationKeys.all }),
  });
}

export function useCreateWorkItem() {
  return useCollaborationMutation<CreateWorkItemInput>(collaborationApi.createWorkItem);
}
