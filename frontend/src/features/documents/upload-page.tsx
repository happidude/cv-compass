import { useEffect, useRef, useState } from "react";
import { Link, useBlocker, useBeforeUnload } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { UploadCloud, X } from "lucide-react";
import { uploadDocument, documentKeys } from "./api";
import { uploadBatch, batchKeys, type BatchItem } from "../batches";
import { config } from "../../shared/config";
import { bytes, displayName } from "../../shared/format";
import { RequestError, errorLabel } from "../../shared/http";
import { Filename, Heading, ErrorNotice } from "../../shared/ui/common";
import { Button } from "../../shared/ui/button";
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
      <Heading
        title="Upload CV"
        description="Chọn một hoặc nhiều file PDF để lưu vào kho CV."
      />
      <div
        className="dropzone"
        onDragOver={(e) => e.preventDefault()}
        onDrop={(e) => {
          e.preventDefault();
          add(e.dataTransfer.files);
        }}
      >
        <UploadCloud aria-hidden="true" size={28} />
        <strong>Kéo thả PDF vào đây</strong>
        <p>
          PDF · tối đa {bytes(config.maxBytes)}/file · 1–{config.maxFiles}{" "}
          file/batch
        </p>
        <label className="button outline file-picker">
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
        <p role="alert" className="notice bad">
          {selectionError}
        </p>
      )}
      <section className="panel upload-list">
        <h2>
          {entries.length ? `${entries.length} file đã chọn` : "Chưa chọn file"}
        </h2>
        {entries.length > 0 && (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Tên file</th>
                  <th>Dung lượng</th>
                  <th>Kết quả</th>
                  <th>
                    <span className="sr-only">Bỏ file</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <tr key={entry.key}>
                    <td>
                      <Filename name={entry.file.name} />
                    </td>
                    <td>{bytes(entry.file.size)}</td>
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
                    <td>
                      {!sending && !outcome && !uncertain && (
                        <Button
                          variant="outline"
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
      </section>
      {sending && (
        <div className="notice">
          <label htmlFor="upload-progress">
            Tiến độ gửi request: {progress}%
          </label>
          <progress id="upload-progress" max={100} value={progress} />
          <p>
            Đây là tiến độ truyền dữ liệu, chưa phải kết quả lưu file hoặc OCR.
          </p>
          <Button variant="outline" onClick={() => controller.current?.abort()}>
            Dừng gửi
          </Button>
        </div>
      )}
      <div aria-live="polite" aria-atomic="true">
        {uncertain ? (
          <div className="notice warn">
            <h2>Chưa xác định kết quả</h2>
            <p>
              Request đã gửi có thể vẫn được máy chủ xử lý. Kiểm tra{" "}
              <Link to="/documents">CV đã lưu</Link> hoặc{" "}
              <Link to="/batches">lịch sử nhập</Link> trước khi gửi lại.
            </p>
          </div>
        ) : error ? (
          <ErrorNotice error={error} />
        ) : (
          outcome && (
            <section className="panel padded">
              <h2>Kết quả từ máy chủ</h2>
              {outcome.items.map((item, i) => (
                <p key={i}>
                  <Filename name={item.name} /> —{" "}
                  {item.documentId ? (
                    <>
                      <span className="badge good">Upload thành công</span>{" "}
                      <Link to={`/documents/${item.documentId}`}>Mở CV</Link>
                    </>
                  ) : (
                    <>
                      <span className="badge bad">
                        Upload thất bại · Chưa lưu
                      </span>{" "}
                      {errorLabel(item.errorCode ?? "") ??
                        "Không thể lưu file."}{" "}
                      {item.errorCode &&
                        /^[A-Z0-9_]{1,100}$/.test(item.errorCode) &&
                        `(${item.errorCode})`}
                    </>
                  )}
                </p>
              ))}
              {outcome.batchId && (
                <Button asChild>
                  <Link to={`/batches/${outcome.batchId}`}>Xem batch</Link>
                </Button>
              )}
              <p className="footnote">
                Upload thành công không đồng nghĩa OCR thành công.
              </p>
            </section>
          )
        )}
      </div>
      <div className="actions end">
        <Button
          variant="outline"
          disabled={sending || !entries.length}
          onClick={reset}
        >
          {outcome || uncertain ? "Bắt đầu lượt mới" : "Xóa danh sách"}
        </Button>
        {!outcome && !uncertain && (
          <Button disabled={sending || !validCount} onClick={() => void send()}>
            {sending
              ? "Đang upload…"
              : `Upload${validCount ? ` ${validCount} file` : ""}`}
          </Button>
        )}
      </div>
      <dialog
        ref={leaveDialog}
        aria-labelledby="leave-title"
        className="leave-dialog"
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
        <div className="actions">
          <Button
            autoFocus
            onClick={() => {
              if (blocker.state === "blocked") blocker.reset();
            }}
          >
            Ở lại
          </Button>
          <Button
            variant="outline"
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
