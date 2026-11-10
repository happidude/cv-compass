import { z } from "zod";
import { config } from "./config";
export type ErrorKind = "network" | "timeout" | "http" | "contract" | "abort";
const safeToken = z
  .string()
  .max(120)
  .regex(/^[a-zA-Z0-9_.:-]+$/);
const apiError = z.object({
  code: safeToken,
  message: z.string(),
  correlationId: safeToken.nullable(),
  details: z.array(z.unknown()),
});
export class RequestError extends Error {
  constructor(
    public kind: ErrorKind,
    public status?: number,
    public code?: string,
    public correlationId?: string,
    public uncertain = false,
  ) {
    super(kind);
  }
}
export function shouldRetry(failures: number, error: unknown) {
  return (
    failures < 2 &&
    error instanceof RequestError &&
    (error.kind === "network" ||
      error.kind === "timeout" ||
      (error.kind === "http" && (error.status ?? 0) >= 500))
  );
}
export function decode<T>(text: string, schema: z.ZodType<T>): T {
  try {
    return schema.parse(JSON.parse(text));
  } catch {
    throw new RequestError("contract");
  }
}
export function httpError(status: number, text: string) {
  let parsed;
  try {
    parsed = apiError.safeParse(JSON.parse(text));
  } catch {
    /* Proxy HTML is still an HTTP error. */
  }
  return new RequestError(
    "http",
    status,
    parsed?.success ? parsed.data.code : undefined,
    parsed?.success ? (parsed.data.correlationId ?? undefined) : undefined,
  );
}
export const errorLabels: Record<string, string> = {
  DOCUMENT_ALREADY_LINKED:
    "Tài liệu đã thuộc hồ sơ khác. Hãy chọn tài liệu chưa liên kết.",
  PROPOSAL_CLOSED: "Đề xuất đã được xử lý. Tải lại danh sách.",
  PROFILE_LIMIT: "Hồ sơ đã đạt giới hạn kinh nghiệm. Liên hệ quản trị viên.",
  AUTH_REQUIRED: "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.",
  ACCESS_DENIED: "Bạn không có quyền truy cập.",
  VERSION_CONFLICT: "Xung đột phiên bản. Tải lại dữ liệu trước khi chỉnh tiếp.",
  STORAGE_FILE_MISSING: "File PDF nguồn không còn trong kho lưu trữ.",
  OCR_FAILED: "OCR thất bại ở trang này.",
  DOCUMENT_TIMEOUT: "Hết thời gian xử lý tài liệu.",
  PAGE_TIMEOUT: "Hết thời gian xử lý trang.",
  BLANK_PAGE: "Trang không có nội dung text.",
  LOW_OCR_CONFIDENCE: "Độ tin cậy của text OCR thấp; cần kiểm tra lại.",
  FILE_CORRUPT: "Không thể đọc file PDF bị hỏng.",
  FILE_ENCRYPTED: "File PDF được mã hóa.",
  PAYLOAD_TOO_LARGE: "File vượt giới hạn dung lượng.",
  FILE_TOO_LARGE: "File vượt giới hạn dung lượng.",
  INVALID_PDF: "File PDF không hợp lệ.",
  INVALID_FILENAME: "Tên file không hợp lệ.",
  INVALID_MULTIPART: "Danh sách file không hợp lệ.",
  DOCUMENT_NOT_FOUND: "Không tìm thấy CV.",
  BATCH_NOT_FOUND: "Không tìm thấy batch.",
  STORAGE_UNAVAILABLE: "Kho lưu trữ tạm thời không sẵn sàng.",
  VALIDATION_ERROR: "Dữ liệu gửi chưa hợp lệ.",
  OCR_PAGE_FAILED: "OCR thất bại ở trang này.",
  TEXT_LIMIT_EXCEEDED: "Text vượt giới hạn xử lý.",
};
export function errorLabel(code: string) {
  return Object.hasOwn(errorLabels, code) ? errorLabels[code] : undefined;
}
export function errorMessage(error: unknown) {
  if (!(error instanceof RequestError)) return "Không thể hoàn thành yêu cầu.";
  if (error.uncertain && (error.kind === "network" || error.kind === "timeout"))
    return "Không rõ thao tác đã được lưu hay chưa. Tải lại dữ liệu trước khi thử lại.";
  if (error.kind === "contract")
    return "Lỗi contract: phản hồi máy chủ không đúng cấu trúc hoặc ID không an toàn.";
  if (error.kind === "timeout")
    return "Yêu cầu quá thời gian. Vui lòng thử cập nhật lại.";
  if (error.kind === "network")
    return "Không thể kết nối máy chủ. Kiểm tra kết nối của bạn.";
  if (error.kind === "abort") return "Yêu cầu đã dừng.";
  return (
    errorLabel(error.code ?? "") ??
    (error.status === 404
      ? "Không tìm thấy dữ liệu."
      : "Máy chủ chưa thể hoàn thành yêu cầu.")
  );
}
export async function get<T>(
  path: string,
  schema: z.ZodType<T>,
  signal?: AbortSignal,
): Promise<T> {
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  if (signal?.aborted) controller.abort();
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, 20000);
  try {
    const response = await fetch(
      new URL(config.apiBase + path, window.location.origin).href,
      {
        signal: controller.signal,
        cache: "no-store",
        credentials: "same-origin",
      },
    );
    const text = await response.text();
    if (!response.ok) {
      if (response.status === 401 && typeof window.dispatchEvent === "function")
        window.dispatchEvent(new Event("cv-auth-expired"));
      throw httpError(response.status, text);
    }
    return decode(text, schema);
  } catch (error) {
    if (error instanceof RequestError) throw error;
    throw new RequestError(
      timedOut ? "timeout" : controller.signal.aborted ? "abort" : "network",
    );
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", abort);
  }
}
// POST has no automatic retry. Any transport failure after send is ambiguous.
export async function upload<T>(
  path: string,
  body: FormData,
  schema: z.ZodType<T>,
  onProgress: (value: number) => void,
  signal: AbortSignal,
): Promise<T> {
  const csrf = await get(
    "/auth/csrf",
    z.object({ token: z.string(), headerName: z.string() }),
    signal,
  );
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    const abort = () => xhr.abort();
    const cleanup = () => signal.removeEventListener("abort", abort);
    if (signal.aborted) {
      reject(new RequestError("abort"));
      return;
    }
    xhr.open(
      "POST",
      new URL(config.apiBase + path, window.location.origin).href,
    );
    xhr.timeout = 120000;
    xhr.setRequestHeader(csrf.headerName, csrf.token);
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable)
        onProgress(Math.round((event.loaded * 100) / event.total));
    };
    xhr.onload = () => {
      cleanup();
      if (xhr.status < 200 || xhr.status >= 300) {
        if (xhr.status === 401)
          window.dispatchEvent(new Event("cv-auth-expired"));
        const e = httpError(xhr.status, xhr.responseText);
        e.uncertain = xhr.status >= 500 || xhr.status === 408;
        reject(e);
        return;
      }
      try {
        resolve(decode(xhr.responseText, schema));
      } catch {
        reject(
          new RequestError("contract", undefined, undefined, undefined, true),
        );
      }
    };
    const fail = (kind: ErrorKind) => {
      cleanup();
      reject(new RequestError(kind, undefined, undefined, undefined, true));
    };
    xhr.onerror = () => fail("network");
    xhr.ontimeout = () => fail("timeout");
    xhr.onabort = () => fail("abort");
    signal.addEventListener("abort", abort, { once: true });
    xhr.send(body);
  });
}
