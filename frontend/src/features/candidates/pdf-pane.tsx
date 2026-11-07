import { useQuery } from "@tanstack/react-query";
import { apiUrl, probePdf } from "../../shared/session";
import { errorMessage } from "../../shared/http";
import { PdfCanvas } from "./pdf-canvas";
export function PdfPane({
  documentId,
  page,
  onPage,
  quote,
}: {
  documentId: string;
  page: number;
  quote?: string;
  onPage: (page: number) => void;
}) {
  const status = useQuery({
    queryKey: ["pdf", documentId],
    queryFn: ({ signal }) => probePdf(documentId, signal).then(() => true),
    retry: false,
  });
  return (
    <section className="panel pdf-pane" aria-label="PDF nguồn">
      <h2>PDF nguồn — trang {page}</h2>
      {quote && <blockquote aria-label="Quote evidence">{quote}</blockquote>}
      {status.isPending ? (
        <p role="status">Đang tải PDF…</p>
      ) : status.error ? (
        <div role="alert">
          <p>{errorMessage(status.error)}</p>
          <button onClick={() => void status.refetch()}>Thử tải PDF lại</button>
        </div>
      ) : (
        <>
          <p>
            <a
              href={
                apiUrl(`/documents/${documentId}/content`) + `#page=${page}`
              }
              target="_blank"
              rel="noreferrer"
            >
              Mở PDF ở tab riêng
            </a>
            {" · "}
            <a href={apiUrl(`/documents/${documentId}/content?download=true`)}>
              Tải PDF gốc
            </a>
          </p>
          <PdfCanvas
            key={documentId + "-" + page}
            documentId={documentId}
            page={page}
            onPage={onPage}
          />
          <p className="footnote">
            Evidence hiển thị quote; chưa có tọa độ để highlight.
          </p>
        </>
      )}
    </section>
  );
}
