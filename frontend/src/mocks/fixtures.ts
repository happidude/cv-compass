// Synthetic data only. Numeric IDs deliberately mirror Java's JSON contract.
export const timestamp = "2026-10-23T02:12:00Z";
export const metadata = (id = 7, name = "Synthetic_CV.pdf") => ({
  id,
  originalFilename: name,
  sizeBytes: 1258291,
  mimeType: "application/pdf",
  sha256: "a".repeat(64),
  status: "PENDING",
  createdAt: timestamp,
});
export const processing = (
  status = "NOT_SCHEDULED",
  availability = "NONE",
) => ({
  jobId: status === "NOT_SCHEDULED" ? null : 11,
  status,
  attemptsStarted: status === "NOT_SCHEDULED" ? null : 1,
  maxAttempts: status === "NOT_SCHEDULED" ? null : 3,
  nextAttemptAt: null,
  startedAt: status === "NOT_SCHEDULED" ? null : timestamp,
  completedAt: ["COMPLETED", "PARTIAL", "FAILED"].includes(status)
    ? timestamp
    : null,
  endReason: null,
  errorCode: null,
  resultAvailability: availability,
});
export const overview = (id = 7, status = "NOT_SCHEDULED") => ({
  metadata: metadata(id),
  batchId: null,
  ingestionStatus: "STORED",
  processing: processing(status),
});
export const noneResult = {
  availability: "NONE",
  extractionId: null,
  status: null,
  pageCount: null,
  pages: [],
  warnings: [],
  errors: [],
  legacyRawText: null,
};
export const pageResult = {
  availability: "PAGE_RESULT",
  extractionId: 10,
  status: "partial",
  pageCount: 4,
  pages: [
    {
      pageNumber: 1,
      status: "ok",
      method: "native",
      text: "HỒ SƠ MẪU\nLập trình viên Backend\nJava · TypeScript",
      rawText: '<script>alert("synthetic")</script>\nHỒ SƠ MẪU',
      warnings: [],
      error: null,
    },
    {
      pageNumber: 2,
      status: "blank",
      method: "none",
      text: "",
      rawText: "",
      warnings: ["BLANK_PAGE"],
      error: null,
    },
    {
      pageNumber: 3,
      status: "failed",
      method: "none",
      text: "",
      rawText: "",
      warnings: [],
      error: { code: "OCR_FAILED", stage: "ocr" },
    },
    {
      pageNumber: 4,
      status: "not_processed",
      method: "none",
      text: "",
      rawText: "",
      warnings: [],
      error: { code: "DOCUMENT_TIMEOUT", stage: "supervisor" },
    },
  ],
  warnings: ["LOW_OCR_CONFIDENCE"],
  errors: [],
  legacyRawText: null,
};
export const details = [
  {
    ...metadata(7, "Synthetic_scan.pdf"),
    batchId: 142,
    ingestionStatus: "STORED",
    processing: processing("PARTIAL", "PAGE_RESULT"),
    ocr: pageResult,
  },
  {
    ...metadata(6, "Synthetic_legacy.pdf"),
    batchId: null,
    ingestionStatus: "STORED",
    processing: processing("COMPLETED", "LEGACY"),
    ocr: {
      ...noneResult,
      availability: "LEGACY",
      extractionId: 9,
      legacyRawText:
        "Synthetic legacy text\n" + "Tiếng Việt an toàn. ".repeat(180),
    },
  },
  {
    ...metadata(5, "Synthetic_pending.pdf"),
    batchId: null,
    ingestionStatus: "STORED",
    processing: processing("PENDING"),
    ocr: noneResult,
  },
  {
    ...metadata(
      4,
      "<img src=x onerror=alert(1)>_" + "Ten_file_rat_dai_".repeat(15) + ".pdf",
    ),
    batchId: null,
    ingestionStatus: "STORED",
    processing: processing("NOT_SCHEDULED"),
    ocr: noneResult,
  },
  {
    ...metadata(3, "Synthetic_failed.pdf"),
    batchId: 139,
    ingestionStatus: "STORED",
    processing: {
      ...processing("FAILED", "PAGE_RESULT"),
      errorCode: "OCR_FAILED",
    },
    ocr: {
      ...pageResult,
      status: "failed",
      pageCount: 1,
      pages: [{ ...pageResult.pages[2], pageNumber: 1 }],
    },
  },
  {
    ...metadata(2, "Synthetic_processing.pdf"),
    batchId: 141,
    ingestionStatus: "STORED",
    processing: processing("PROCESSING"),
    ocr: noneResult,
  },
  {
    ...metadata(1, "Synthetic_unknown.pdf"),
    batchId: null,
    ingestionStatus: "STORED",
    processing: processing("FUTURE_STATE"),
    ocr: noneResult,
  },
];
export const documentPage = {
  content: details.map((d) => ({
    metadata: Object.fromEntries(
      Object.entries(d).filter(([key]) =>
        [
          "id",
          "originalFilename",
          "sizeBytes",
          "mimeType",
          "sha256",
          "status",
          "createdAt",
        ].includes(key),
      ),
    ),
    batchId: d.batchId,
    ingestionStatus: d.ingestionStatus,
    processing: d.processing,
  })),
  size: 20,
  hasNext: false,
  nextBeforeId: null,
};
export const batch = (id = 142, status = "PARTIAL_FAILED") => ({
  id,
  totalFiles: 4,
  successCount: 3,
  failedCount: 1,
  status,
  createdAt: timestamp,
  completedAt: status === "PROCESSING" ? null : timestamp,
});
export const batches = [
  batch(),
  batch(141, "PROCESSING"),
  { ...batch(140, "COMPLETED"), failedCount: 0, successCount: 4 },
  { ...batch(139, "FAILED"), successCount: 0, failedCount: 4 },
];
export const items = [
  {
    fileOrder: 0,
    displayName: "Synthetic_scan.pdf",
    documentId: 7,
    errorCode: null,
  },
  {
    fileOrder: 1,
    displayName: "Synthetic_legacy.pdf",
    documentId: 6,
    errorCode: null,
  },
  {
    fileOrder: 2,
    displayName: "Synthetic_pending.pdf",
    documentId: 5,
    errorCode: null,
  },
  {
    fileOrder: 3,
    displayName: "Synthetic_invalid.pdf",
    documentId: null,
    errorCode: "INVALID_PDF",
  },
];
export const apiFailure = {
  code: "INTERNAL_ERROR",
  message: "Never display this private server message",
  correlationId: "synthetic-trace-123",
  details: [],
};
