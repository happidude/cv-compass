import { useEffect, useRef, useState } from "react";
import { Link, useBlocker, useBeforeUnload } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { FileText, UploadCloud, X } from "lucide-react";
import { uploadDocument, documentKeys } from "./api";
import { uploadBatch, batchKeys, type BatchItem } from "../batches";
import { config } from "../../shared/config";
import { bytes, displayName } from "../../shared/format";
import { RequestError, errorLabel } from "../../shared/http";
import {
  Badge,
  Button,
  ErrorNotice,
  Filename,
  Notice,
  PageHeader,
  Panel,
  RouteLine,
} from "../../shared/ui";
type Entry = { file: File; key: number; error?: string };
type Outcome = {
  items: {
    name: string;
    documentId: string | null;
    errorCode: string | null;
  }[];
  batchId?: string;
};
export function validateFile(file: File) {
  return file.size === 0
    ? "File trống."
    : file.size > config.maxBytes
      ? `Vượt giới hạn ${bytes(config.maxBytes)}.`
      : !/\.pdf$/i.test(file.name) ||
          (file.type !== "" && file.type !== "application/pdf")
        ? "Chỉ nhận file PDF."
        : undefined;
}
export function UploadPage() {
  const [entries, setEntries] = useState<Entry[]>([]);
  const [sending, setSending] = useState(false);
  const [progress, setProgress] = useState(0);
  const [outcome, setOutcome] = useState<Outcome>();
  const [error, setError] = useState<unknown>();
  const [uncertain, setUncertain] = useState(false);
  const [selectionError, setSelectionError] = useState("");
  const nextKey = useRef(0);
  const busy = useRef(false);
  const controller = useRef<AbortController | null>(null);
  const client = useQueryClient();
  const blocker = useBlocker(sending);
  const leaveDialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    if (blocker.state === "blocked") leaveDialog.current?.showModal();
    else leaveDialog.current?.close();
  }, [blocker.state]);
  useBeforeUnload((event) => {
    if (busy.current) {
      event.preventDefault();
      event.returnValue = "";
    }
  });
  useEffect(() => () => controller.current?.abort(), []);
  function add(files: FileList | File[]) {
    if (busy.current || outcome || uncertain) return;
    if (entries.length + files.length > config.maxFiles) {
      setSelectionError(`Chọn tối đa ${config.maxFiles} file mỗi lần.`);
      return;
    }
    setSelectionError("");
    setError(undefined);
    const additions = Array.from(files).map((file) => ({
      file,
      key: nextKey.current++,
      error: validateFile(file),
    }));
    setEntries((prev) => [...prev, ...additions]);
  }
  async function send() {
    if (busy.current || outcome || uncertain) return;
    const valid = entries.filter((e) => !e.error);
    if (!valid.length) return;
    busy.current = true;
    setSending(true);
    setError(undefined);
    setProgress(0);
    controller.current = new AbortController();
    try {
      if (valid.length === 1) {
        const result = await uploadDocument(
          valid[0].file,
          setProgress,
          controller.current.signal,
        );
        setOutcome({
          items: [
            {
              name: result.originalFilename,
              documentId: result.id,
              errorCode: null,
            },
          ],
        });
      } else {
        const result = await uploadBatch(
          valid.map((e) => e.file),
          setProgress,
          controller.current.signal,
        );
        setOutcome({
          batchId: result.batch.id,
          items: result.items.map((i: BatchItem) => ({
            name: i.displayName,
            documentId: i.documentId,
            errorCode: i.errorCode,
          })),
        });
      }
      void client.invalidateQueries({ queryKey: documentKeys.lists });
      void client.invalidateQueries({ queryKey: batchKeys.all });
    } catch (e) {
      setError(e);
      if (e instanceof RequestError && e.uncertain) {
        setUncertain(true);
        void client.invalidateQueries({ queryKey: documentKeys.lists });
        void client.invalidateQueries({ queryKey: batchKeys.all });
      }
    } finally {
      busy.current = false;
      setSending(false);
    }
  }
  const validCount = entries.filter((e) => !e.error).length;
  function reset() {
    setEntries([]);
    setOutcome(undefined);
    setError(undefined);
    setUncertain(false);
    setProgress(0);
    setSelectionError("");
  }
  return (
    <>
      <PageHeader
        title="Upload CV"
        description="Chọn một hoặc nhiều file PDF để lưu vào kho CV."
      />
      <div className="upload-layout">
        <div className="stack">
          <div
            className="dropzone"
            data-disabled={sending || !!outcome || uncertain || undefined}
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              add(e.dataTransfer.files);
            }}
          >
            <span className="dropzone__icon" aria-hidden="true">
              <UploadCloud size={24} />
            </span>
            <strong>Kéo thả PDF vào đây</strong>
            <p>
              PDF · tối đa {bytes(config.maxBytes)}/file · 1–{config.maxFiles}{" "}
              file/batch
            </p>
            <label className="btn btn--secondary file-picker">
              Chọn file
              <input
                aria-label="Chọn file PDF"
                type="file"
                accept="application/pdf,.pdf"
                multiple
                disabled={sending || !!outcome || uncertain}
                onChange={(e) => {
                  if (e.target.files) add(e.target.files);
                  e.target.value = "";
                }}
              />
            </label>
          </div>
          {selectionError && (
            <p role="alert" className="notice notice--bad">
              {selectionError}
            </p>
          )}
          <Panel
            className="upload-list"
            title={
              entries.length
                ? `${entries.length} file đã chọn`
                : "Chưa chọn file"
            }
            subtitle={
              entries.length > 0
                ? `${validCount} file hợp lệ sẽ được gửi`
                : "Danh sách file sẽ hiện ở đây trước khi gửi."
            }
          >
            {entries.length > 0 && (
              <div className="table-scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Tên file</th>
                      <th className="cell-num">Dung lượng</th>
                      <th>Kết quả</th>
                      <th className="cell-action">
                        <span className="sr-only">Bỏ file</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {entries.map((entry) => (
                      <tr key={entry.key}>
                        <td>
                          <span className="file-cell">
                            <FileText size={16} aria-hidden="true" />
                            <Filename name={entry.file.name} />
                          </span>
                        </td>
                        <td className="cell-num cell-muted">
                          {bytes(entry.file.size)}
                        </td>
                        <td>
                          {entry.error ? (
                            <span className="text-bad">
                              Chưa gửi — {entry.error}
                            </span>
                          ) : outcome ? (
                            "Đã gửi — xem kết quả bên dưới"
                          ) : uncertain ? (
                            "Chưa xác định kết quả"
                          ) : sending ? (
                            "Đang gửi trong request chung"
                          ) : (
                            "Sẵn sàng"
                          )}
                        </td>
                        <td className="cell-action">
                          {!sending && !outcome && !uncertain && (
                            <Button
                              variant="ghost"
                              size="sm"
                              icon
                              aria-label={`Bỏ ${displayName(entry.file.name)}`}
                              onClick={() =>
                                setEntries((prev) =>
                                  prev.filter((e) => e.key !== entry.key),
                                )
                              }
                            >
                              <X size={15} aria-hidden="true" />
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
          {sending && (
            <Notice>
              <label htmlFor="upload-progress">
                Tiến độ gửi request: {progress}%
              </label>
              <progress
                className="progress"
                id="upload-progress"
                max={100}
                value={progress}
              />
              <p className="notice__meta">
                Đây là tiến độ truyền dữ liệu, chưa phải kết quả lưu file hoặc
                OCR.
              </p>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => controller.current?.abort()}
              >
                Dừng gửi
              </Button>
            </Notice>
          )}
          <div aria-live="polite" aria-atomic="true">
            {uncertain ? (
              <Notice tone="warn">
                <h2>Chưa xác định kết quả</h2>
                <p>
                  Request đã gửi có thể vẫn được máy chủ xử lý. Kiểm tra{" "}
                  <Link to="/documents">CV đã lưu</Link> hoặc{" "}
                  <Link to="/batches">lịch sử nhập</Link> trước khi gửi lại.
                </p>
              </Notice>
            ) : error ? (
              <ErrorNotice error={error} />
            ) : (
              outcome && (
                <Panel
                  title="Kết quả từ máy chủ"
                  actions={
                    outcome.batchId && (
                      <Button asChild size="sm">
                        <Link to={`/batches/${outcome.batchId}`}>
                          Xem batch
                        </Link>
                      </Button>
                    )
                  }
                  footer="Upload thành công không đồng nghĩa OCR thành công."
                >
                  <ul className="result-list">
                    {outcome.items.map((item, i) => (
                      <li key={i}>
                        <Filename name={item.name} />
                        {item.documentId ? (
                          <>
                            <Badge tone="good" mark>
                              Upload thành công
                            </Badge>
                            <Link to={`/documents/${item.documentId}`}>
                              Mở CV
                            </Link>
                          </>
                        ) : (
                          <>
                            <Badge tone="bad" mark>
                              Upload thất bại · Chưa lưu
                            </Badge>
                            <span className="muted">
                              {errorLabel(item.errorCode ?? "") ??
                                "Không thể lưu file."}{" "}
                              {item.errorCode &&
                                /^[A-Z0-9_]{1,100}$/.test(item.errorCode) &&
                                `(${item.errorCode})`}
                            </span>
                          </>
                        )}
                      </li>
                    ))}
                  </ul>
                </Panel>
              )
            )}
          </div>
          <div className="btn-row btn-row--end">
            <Button
              variant="secondary"
              disabled={sending || !entries.length}
              onClick={reset}
            >
              {outcome || uncertain ? "Bắt đầu lượt mới" : "Xóa danh sách"}
            </Button>
            {!outcome && !uncertain && (
              <Button
                disabled={sending || !validCount}
                onClick={() => void send()}
              >
                {sending
                  ? "Đang upload…"
                  : `Upload${validCount ? ` ${validCount} file` : ""}`}
              </Button>
            )}
          </div>
        </div>
        <aside className="upload-guide" aria-labelledby="upload-guide-title">
          <h2 id="upload-guide-title">Sau khi upload</h2>
          <RouteLine
            vertical
            label="Các bước sau khi upload"
            stops={[
              {
                label: "Lưu file",
                state: "done",
                detail: "PDF gốc được giữ nguyên, không bị ghi đè.",
              },
              {
                label: "OCR",
                state: "waiting",
                detail: "Chạy bất đồng bộ; theo dõi trong Quản lý CV.",
              },
              {
                label: "Hồ sơ",
                state: "todo",
                detail: "Giá trị chưa chắc chắn chờ HR xác nhận.",
              },
            ]}
          />
          <p className="form-note">
            Nhiều file được gửi trong một batch; kết quả từng file xem ở Lịch sử
            nhập.
          </p>
        </aside>
      </div>
      <dialog
        ref={leaveDialog}
        aria-labelledby="leave-title"
        className="dialog"
        onCancel={(e) => {
          e.preventDefault();
          if (blocker.state === "blocked") blocker.reset();
        }}
      >
        <h2 id="leave-title">Upload đang diễn ra</h2>
        <p>
          Rời trang sẽ dừng chờ kết quả. Máy chủ có thể vẫn lưu file; hãy kiểm
          tra dữ liệu trước khi gửi lại.
        </p>
        <div className="btn-row">
          <Button
            autoFocus
            onClick={() => {
              if (blocker.state === "blocked") blocker.reset();
            }}
          >
            Ở lại
          </Button>
          <Button
            variant="secondary"
            onClick={() => {
              controller.current?.abort();
              if (blocker.state === "blocked") blocker.proceed();
            }}
          >
            Rời trang
          </Button>
        </div>
      </dialog>
    </>
  );
}
