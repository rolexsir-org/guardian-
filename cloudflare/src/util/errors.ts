/** Error types used across the API. Nothing internal is ever exposed to clients. */

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly retryAfterSeconds: number | undefined;

  constructor(status: number, code: string, message: string, retryAfterSeconds?: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export const badRequest = (code: string, message: string): ApiError => new ApiError(400, code, message);
export const unauthorized = (code = "unauthenticated", message = "Authentication required."): ApiError =>
  new ApiError(401, code, message);
export const forbidden = (code = "forbidden", message = "You are not allowed to perform this action."): ApiError =>
  new ApiError(403, code, message);
export const notFound = (code = "not_found", message = "Resource not found."): ApiError =>
  new ApiError(404, code, message);
export const conflict = (code: string, message: string): ApiError => new ApiError(409, code, message);
export const payloadTooLarge = (code: string, message: string): ApiError => new ApiError(413, code, message);
export const unprocessable = (code: string, message: string): ApiError => new ApiError(422, code, message);
export const rateLimited = (retryAfterSeconds: number): ApiError =>
  new ApiError(429, "rate_limited", "Too many requests. Please try again later.", retryAfterSeconds);
export const unavailable = (code: string, message: string): ApiError => new ApiError(503, code, message);

/** Redacts any secret-looking material before it can reach logs. */
export function redactForLog(value: string): string {
  return value
    .replace(/(bearer\s+)[A-Za-z0-9._~+/-]+=*/gi, "$1[redacted]")
    .replace(/((?:token|secret|password|refresh|authorization)"?\s*[:=]\s*"?)[^"\s,&]+/gi, "$1[redacted]");
}
