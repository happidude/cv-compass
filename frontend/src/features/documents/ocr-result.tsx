import { errorLabel } from "../../shared/http";
import { Empty, LongText, Notice, Status, statusInfo } from "../../shared/ui";
import type { DocumentDetail } from "./schema";
export function OcrResult({
  ocr,
  processing,
}: Pick<DocumentDetail, "ocr" | "processing">) {
  if (ocr.availability === "NONE")
    return (
      <Empty>
        Chưa có kết quả OCR. {statusInfo("job", processing.status)[2]}
      </Empty>
    );
  if (ocr.availability === "LEGACY")
    return (
      <div className="stack">
        <Notice tone="neutral">
          <p>Kết quả cũ — nội dung được lưu nguyên khối.</p>
        </Notice>
        {ocr.legacyRawText === null ? (
          <Empty>Kết quả cũ không có văn bản</Empty>
        ) : (
          <LongText text={ocr.legacyRawText} />
        )}
      </div>
    );
  if (ocr.availability !== "PAGE_RESULT")
    return <Empty>Không xác định loại kết quả OCR.</Empty>;
  return (
    <div className="stack">
      {ocr.status && (
        <p className="ocr-summary">
          Kết quả: <Status domain="result" value={ocr.status} />
        </p>
      )}
      {ocr.warnings.map((code, i) => (
        <Issue key={i} code={code} warning />
      ))}
      {ocr.errors.map((e, i) => (
        <Issue key={i} {...e} />
      ))}
      {!ocr.pages.length && <Empty>Chưa có trang kết quả.</Empty>}
      {ocr.pages.map((page) => (
        <article className="ocr-page" key={page.pageNumber}>
          <header className="ocr-page__header">
            <h3>
              Trang {page.pageNumber}{" "}
              <Status domain="page" value={page.status} />
            </h3>
          </header>
          <h4>Nội dung trích xuất</h4>
          {page.rawText ? (
            <LongText text={page.rawText} />
          ) : (
            <p className="muted">{statusInfo("page", page.status)[2]}</p>
          )}
          {page.warnings.map((code, i) => (
            <Issue key={i} code={code} warning />
          ))}
          {page.error && <Issue {...page.error} />}
        </article>
      ))}
    </div>
  );
}
export function Issue({
  code,
  stage,
  warning = false,
}: {
  code: string;
  stage?: string;
  warning?: boolean;
}) {
  const safe = /^[A-Za-z0-9_.:-]{1,100}$/;
  return (
    <Notice tone={warning ? "warn" : "bad"}>
      <p>
        {errorLabel(code) ??
          (warning
            ? "Có cảnh báo khi xử lý OCR."
            : "Có lỗi khi xử lý OCR.")}{" "}
        {safe.test(code) && <span>Mã: {code}</span>}{" "}
        {stage && safe.test(stage) && <span> · Bước: {stage}</span>}
      </p>
    </Notice>
  );
}
