import { useState } from "react";
import { Link2 } from "lucide-react";
import { validRouteId } from "../../shared/schema";
import { Button, Empty } from "../../shared/ui";
import { PdfPane } from "./pdf-pane";
import type { Profile } from "./schema";
/** Right-hand source column: pick a linked PDF and read the cited page. */
export function SourcePane({
  documents,
  selectedId,
  page,
  quote,
  saving,
  onSelect,
  onPage,
  onLink,
}: {
  documents: Profile["documents"];
  selectedId?: string;
  page: number;
  quote: string;
  saving: boolean;
  onSelect: (documentId: string) => void;
  onPage: (page: number) => void;
  onLink: (documentId: number) => void;
}) {
  const [linkDocumentId, setLinkDocumentId] = useState("");
  return (
    <aside className="source-column" aria-label="Nguồn đối chiếu">
      <div className="panel source-panel">
        <div className="source-panel__picker">
          <label className="field" htmlFor="source-document">
            Tài liệu nguồn
          </label>
          <select
            id="source-document"
            className="select"
            value={selectedId ?? ""}
            onChange={(event) => onSelect(event.target.value)}
          >
            {documents.map((doc) => (
              <option key={doc.id} value={doc.id}>
                {doc.filename} · nhập hệ thống{" "}
                {new Date(doc.importedAt).toLocaleDateString("vi-VN")}
              </option>
            ))}
          </select>
          <p className="form-note">
            Ngày nhận CV và ngày nội dung cập nhật: chưa có nguồn xác định.
          </p>
        </div>
        {selectedId ? (
          <PdfPane
            key={selectedId}
            documentId={selectedId}
            page={page}
            quote={quote}
            onPage={onPage}
          />
        ) : (
          <Empty>Chưa có tài liệu nguồn được liên kết.</Empty>
        )}
      </div>
      <section className="panel link-panel" aria-labelledby="link-title">
        <h2 id="link-title">
          <Link2 size={14} aria-hidden="true" />
          Liên kết thêm tài liệu
        </h2>
        <form
          className="link-panel__form"
          onSubmit={(event) => {
            event.preventDefault();
            if (validRouteId(linkDocumentId)) onLink(Number(linkDocumentId));
          }}
        >
          <label className="field">
            ID tài liệu đã nhập cần liên kết
            <input
              className="input"
              inputMode="numeric"
              value={linkDocumentId}
              onChange={(event) => setLinkDocumentId(event.target.value)}
              required
            />
          </label>
          <Button
            type="submit"
            variant="secondary"
            disabled={saving || !validRouteId(linkDocumentId)}
          >
            Liên kết tài liệu với hồ sơ này
          </Button>
          <p className="form-note">
            Chỉ gắn tài liệu chưa thuộc ứng viên khác; không tự gộp hai hồ sơ.
          </p>
        </form>
      </section>
    </aside>
  );
}
