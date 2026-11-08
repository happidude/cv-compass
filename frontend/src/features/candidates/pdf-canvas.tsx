import { useEffect, useRef, useState } from "react";
import type { PDFDocumentLoadingTask, RenderTask } from "pdfjs-dist";
import workerUrl from "pdfjs-dist/build/pdf.worker.min.mjs?url";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { apiUrl, authExpired } from "../../shared/session";
import { Button } from "../../shared/ui";

export function PdfCanvas({
  documentId,
  page,
  onPage,
}: {
  documentId: string;
  page: number;
  onPage: (page: number) => void;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "error">(
    "loading",
  );
  const [pages, setPages] = useState(0);
  const [text, setText] = useState("");
  useEffect(() => {
    let cancelled = false;
    let loading: PDFDocumentLoadingTask | undefined;
    let render: RenderTask | undefined;
    void (async () => {
      try {
        const pdfjs = await import("pdfjs-dist");
        if (cancelled) return;
        pdfjs.GlobalWorkerOptions.workerSrc = workerUrl;
        const assets =
          window.location.origin + import.meta.env.BASE_URL + "pdfjs-assets/";
        loading = pdfjs.getDocument({
          url: apiUrl(`/documents/${documentId}/content`),
          withCredentials: true,
          rangeChunkSize: 65536,
          disableAutoFetch: true,
          disableStream: true,
          enableXfa: false,
          cMapUrl: assets + "cmaps/",
          standardFontDataUrl: assets + "standard_fonts/",
          wasmUrl: assets + "wasm/",
          iccUrl: assets + "iccs/",
        });
        const document = await loading.promise;
        if (cancelled) return;
        const selected = await document.getPage(page);
        const target = canvas.current;
        if (!target || cancelled) return;
        const natural = selected.getViewport({ scale: 1 });
        const viewport = selected.getViewport({
          scale: Math.min(1.5, 1600 / natural.width, 2400 / natural.height),
        });
        target.width = Math.ceil(viewport.width);
        target.height = Math.ceil(viewport.height);
        render = selected.render({ canvas: target, viewport });
        await render.promise;
        const content = await selected.getTextContent();
        if (cancelled) return;
        setText(
          content.items
            .map((item) => ("str" in item ? item.str : ""))
            .join(" "),
        );
        setPages(document.numPages);
        setStatus("ready");
      } catch (error) {
        if (!cancelled) {
          if (
            error &&
            typeof error === "object" &&
            "status" in error &&
            error.status === 401
          )
            authExpired();
          setStatus("error");
        }
      }
    })();
    return () => {
      cancelled = true;
      render?.cancel();
      void loading?.destroy();
    };
  }, [documentId, page]);
  return (
    <div
      className="pdf-canvas-view"
      data-document-id={documentId}
      data-page-number={page}
    >
      {status === "loading" && (
        <p role="status" className="muted">
          Đang hiển thị trang PDF…
        </p>
      )}
      {status === "error" && (
        <p role="alert" className="notice notice--bad">
          Không thể hiển thị trang PDF này. Thử tải lại hoặc mở/tải PDF gốc.
        </p>
      )}
      {status === "ready" && (
        <div className="pdf-pager">
          <Button
            variant="secondary"
            size="sm"
            disabled={page <= 1}
            onClick={() => onPage(page - 1)}
          >
            <ChevronLeft size={14} aria-hidden="true" />
            Trang PDF trước
          </Button>
          <p className="nums">
            Trang {page}/{pages}
          </p>
          <Button
            variant="secondary"
            size="sm"
            disabled={page >= pages}
            onClick={() => onPage(page + 1)}
          >
            Trang PDF tiếp
            <ChevronRight size={14} aria-hidden="true" />
          </Button>
        </div>
      )}
      <div className="pdf-sheet">
        <canvas
          ref={canvas}
          aria-label={`PDF tài liệu ${documentId}, trang ${page}`}
          role="img"
          hidden={status === "error"}
        />
      </div>
      {status === "ready" && (
        <details className="disclosure">
          <summary>Văn bản của trang PDF (nếu có)</summary>
          <pre className="text-block text-block--scroll">
            {text || "Trang PDF không có lớp văn bản."}
          </pre>
        </details>
      )}
    </div>
  );
}
