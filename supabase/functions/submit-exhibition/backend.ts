import { createClient, type SupabaseClient } from "@supabase/supabase-js";

export interface SubmissionMediaInput {
  asset_id: string;
  object_path: string;
  mime_type: "image/jpeg" | "image/png";
  byte_size: number;
  original_filename: string;
}

export interface SubmissionCreateInput {
  submissionId: string;
  submitterEmail: string;
  payload: Record<string, string>;
  sourceIpHash: string;
  userAgent: string;
  media: SubmissionMediaInput[];
}

export interface SubmissionBackend {
  checkRateLimit(submitterEmail: string, sourceIpHash: string): Promise<void>;
  upload(path: string, file: File): Promise<void>;
  create(input: SubmissionCreateInput): Promise<void>;
  remove(paths: string[]): Promise<void>;
}

interface RpcError {
  code?: string;
  message?: string;
}

export class SubmissionBackendError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "SubmissionBackendError";
  }
}

function requiredEnvironment(
  environment: Record<string, string>,
  name: string,
): string {
  const value = environment[name]?.trim();
  if (!value) {
    throw new SubmissionBackendError(
      "server_configuration_missing",
      `${name} is required.`,
    );
  }
  return value;
}

function secretCredential(environment: Record<string, string>): string {
  const direct = environment.SUPABASE_SECRET_KEY?.trim() ||
    environment.SUPABASE_SERVICE_ROLE_KEY?.trim();
  if (direct) return direct;

  const encoded = environment.SUPABASE_SECRET_KEYS?.trim();
  if (encoded) {
    try {
      const parsed: unknown = JSON.parse(encoded);
      if (typeof parsed === "string" && parsed.trim()) return parsed.trim();
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        const keys = parsed as Record<string, unknown>;
        for (const name of ["submit-exhibition", "service_role", "default"]) {
          const value = keys[name];
          if (typeof value === "string" && value.trim()) return value.trim();
        }
      }
    } catch {
      // One generic configuration error below; never return secret contents.
    }
  }
  throw new SubmissionBackendError(
    "server_configuration_missing",
    "A Supabase server secret is required.",
  );
}

function rpcError(error: RpcError): SubmissionBackendError {
  const message = error.message ?? "submission_create_failed";
  if (message.includes("submission_rate_limited")) {
    return new SubmissionBackendError(
      "submission_rate_limited",
      "Too many submissions were received.",
    );
  }
  return new SubmissionBackendError(
    "submission_create_failed",
    "The submission could not be stored.",
  );
}

class SupabaseSubmissionBackend implements SubmissionBackend {
  constructor(private readonly client: SupabaseClient) {}

  async checkRateLimit(
    submitterEmail: string,
    sourceIpHash: string,
  ): Promise<void> {
    const { error } = await this.client.rpc(
      "check_exhibition_submission_rate_limit",
      {
        p_submitter_email: submitterEmail,
        p_source_ip_hash: sourceIpHash,
      },
    );
    if (error) throw rpcError(error);
  }

  async upload(path: string, file: File): Promise<void> {
    const { error } = await this.client.storage
      .from("exhibition-media")
      .upload(path, file, {
        cacheControl: "3600",
        contentType: file.type,
        upsert: false,
      });
    if (error) {
      throw new SubmissionBackendError(
        "submission_upload_failed",
        "An image could not be uploaded.",
      );
    }
  }

  async create(input: SubmissionCreateInput): Promise<void> {
    const { error } = await this.client.rpc("create_exhibition_submission", {
      p_submission_id: input.submissionId,
      p_submitter_email: input.submitterEmail,
      p_payload: input.payload,
      p_source_ip_hash: input.sourceIpHash,
      p_user_agent: input.userAgent,
      p_media: input.media,
    });
    if (error) throw rpcError(error);
  }

  async remove(paths: string[]): Promise<void> {
    if (paths.length === 0) return;
    await this.client.storage.from("exhibition-media").remove(paths);
  }
}

export function createSubmissionBackend(
  environment: Record<string, string>,
): SubmissionBackend {
  const url = requiredEnvironment(environment, "SUPABASE_URL");
  const credential = secretCredential(environment);
  return new SupabaseSubmissionBackend(
    createClient(url, credential, {
      auth: {
        autoRefreshToken: false,
        persistSession: false,
      },
    }),
  );
}
