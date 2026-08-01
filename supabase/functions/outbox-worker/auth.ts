import {
  type OpaqueTokenValidation,
  validateOpaqueToken,
} from "../_shared/opaque_token.ts";

export type WorkerTokenValidation = OpaqueTokenValidation;

export function validateWorkerToken(token: string): WorkerTokenValidation {
  return validateOpaqueToken(token);
}
