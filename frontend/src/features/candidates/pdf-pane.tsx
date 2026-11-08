import { useQuery } from "@tanstack/react-query";
import { apiUrl, probePdf } from "../../shared/session";
import { Download, ExternalLink } from "lucide-react";
import { errorMessage } from "../../shared/http";
import { Button } from "../../shared/ui";
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
    <section className="pdf-pane" aria-label="PDF nguồn">
      <div className="pdf-pane__head">
        <h2>PDF nguồn — trang {page}</h2>
        {!status.isPending && !status.error && (
          <div className="btn-row">
            <a
              className="btn btn--ghost btn--sm"
              href={
                apiUrl(`/documents/${documentId}/content`) + `#page=${page}`
              }
              target="_blank"
              rel="noreferrer"
            >
              <ExternalLink size={14} aria-hidden="true" />
              Mở PDF ở tab riêng
            </a>
            <a
              className="btn btn--ghost btn--sm"
              href={apiUrl(`/documents/${documentId}/content?download=true`)}
            >
              <Download size={14} aria-hidden="true" />
              Tải PDF gốc
            </a>
          </div>
        )}
      </div>
      {quote && (
        <blockquote className="quote" aria-label="Quote evidence">
          {quote}
        </blockquote>
      )}
      {status.isPending ? (
        <p role="status" className="muted">
          Đang tải PDF…
        </p>
      ) : status.error ? (
        <div role="alert" className="notice notice--bad">
          <p>{errorMessage(status.error)}</p>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => void status.refetch()}
          >
            Thử tải PDF lại
          </Button>
        </div>
      ) : (
        <>
          <PdfCanvas
            key={documentId + "-" + page}
            documentId={documentId}
            page={page}
            onPage={onPage}
          />
          <p className="form-note">
            Evidence hiển thị quote; chưa có tọa độ để highlight.
          </p>
        </>
      )}
    </section>
  );
}
