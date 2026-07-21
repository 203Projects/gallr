export interface WorkerTokenValidation {
  valid: boolean;
  reason?: string;
}

export function validateWorkerToken(token: string): WorkerTokenValidation {
  if (token.length < 32 || token.length > 512) {
    return {
      valid: false,
      reason: "token length must be between 32 and 512 characters",
    };
  }
  if (/\s|[\u0000-\u001f\u007f]/.test(token)) {
    return {
      valid: false,
      reason: "token must not contain whitespace or control characters",
    };
  }

  const characterClasses = [/[a-z]/, /[A-Z]/, /[0-9]/, /[^A-Za-z0-9]/]
    .filter((pattern) => pattern.test(token)).length;
  if (characterClasses < 3 || new Set(token).size < 8) {
    return {
      valid: false,
      reason:
        "token must contain at least three character classes and eight distinct characters",
    };
  }
  return { valid: true };
}
