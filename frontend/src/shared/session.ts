import { z } from "zod";
import { config } from "./config";
import { get, RequestError, decode, httpError } from "./http";

export const userSchema = z.object({
  username: z.string(),
  roles: z.array(z.string()),
});
export const currentUser = () => get("/auth/me", userSchema);
export const csrfToken = () =>
  get("/auth/csrf", z.object({ token: z.string(), headerName: z.string() }));
export function apiUrl(path: string) {
  return new URL(config.apiBase + path, window.location.origin).href;
}
export function authExpired() {
  window.dispatchEvent(new Event("cv-auth-expired"));
}
function transportError(
  error: unknown,
  signal?: AbortSignal,
  timedOut = false,
  uncertain = false,
) {
  if (error instanceof RequestError) return error;
  const kind = timedOut ? "timeout" : signal?.aborted ? "abort" : "network";
  return new RequestError(
    kind,
    undefined,
    undefined,
    undefined,
    uncertain && (kind === "network" || kind === "timeout"),
  );
}
export async function login(username: string, password: string) {
  const csrf = await csrfToken();
  try {
    const response = await fetch(apiUrl("/auth/login"), {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        [csrf.headerName]: csrf.token,
      },
      body: new URLSearchParams({ username, password }),
    });
    if (!response.ok)
      throw new RequestError("http", response.status, "LOGIN_FAILED");
  } catch (error) {
    throw transportError(error);
  }
}
export async function change<T>(
  path: string,
  method: string,
  data: unknown,
  schema: z.ZodType<T>,
): Promise<T> {
  const csrf = await csrfToken();
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, 30000);
  try {
    const response = await fetch(apiUrl(path), {
      method,
      signal: controller.signal,
      credentials: "same-origin",
      headers: {
        "Content-Type": "application/json",
        [csrf.headerName]: csrf.token,
      },
      body: JSON.stringify(data),
    });
    const text = await response.text();
    if (!response.ok) {
      if (response.status === 401) authExpired();
      throw httpError(response.status, text);
    }
    return decode(text, schema);
  } catch (error) {
    throw transportError(error, controller.signal, timedOut, true);
  } finally {
    clearTimeout(timer);
  }
}
export async function logout() {
  const csrf = await csrfToken();
  try {
    const response = await fetch(apiUrl("/auth/logout"), {
      method: "POST",
      credentials: "same-origin",
      headers: { [csrf.headerName]: csrf.token },
    });
    if (!response.ok) throw new RequestError("http", response.status);
  } catch (error) {
    throw transportError(error);
  }
}
export async function probePdf(documentId: string, signal: AbortSignal) {
  try {
    const response = await fetch(apiUrl(`/documents/${documentId}/content`), {
      credentials: "same-origin",
      cache: "no-store",
      headers: { Range: "bytes=0-0" },
      signal,
    });
    if (response.status === 401) authExpired();
    if (!response.ok) {
      const text = await response.text();
      let code: string | undefined;
      try {
        const parsed = z
          .object({
            code: z.enum([
              "STORAGE_FILE_MISSING",
              "DOCUMENT_NOT_FOUND",
              "STORAGE_UNAVAILABLE",
              "ACCESS_DENIED",
              "AUTH_REQUIRED",
            ]),
          })
          .parse(JSON.parse(text));
        code = parsed.code;
      } catch {
        /* Safe generic error. */
      }
      throw new RequestError("http", response.status, code);
    }
    await response.body?.cancel();
  } catch (error) {
    throw transportError(error, signal);
  }
}
