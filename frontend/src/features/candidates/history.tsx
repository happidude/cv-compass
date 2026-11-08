import { Button, Empty, ErrorNotice, Loading } from "../../shared/ui";
import type { UseQueryResult } from "@tanstack/react-query";
import type { z } from "zod";
import type { historySchema } from "./schema";
type History = z.infer<typeof historySchema>;
/** Version history; each entry keeps the literal before/after payload. */
export function HistorySection({
  history,
  before,
  onBefore,
}: {
  history: UseQueryResult<History>;
  before?: string;
  onBefore: (before?: string) => void;
}) {
  return (
    <section
      className="profile-section"
      id="history"
      aria-labelledby="history-title"
    >
      <header className="profile-section__header">
        <h2 id="history-title">Lịch sử thay đổi</h2>
      </header>
      <div className="profile-section__body">
        {history.isPending ? (
          <Loading label="Đang tải lịch sử…" rows={3} />
        ) : history.error ? (
          <ErrorNotice
            error={history.error}
            retry={() => void history.refetch()}
          />
        ) : (
          <>
            {history.data.length === 0 ? (
              <Empty inline>Chưa có lịch sử.</Empty>
            ) : (
              <ol className="timeline">
                {history.data.map((item) => (
                  <li key={item.id} className="timeline__item">
                    <details>
                      <summary>
                        v{item.version} · {item.actor} · {item.action} ·{" "}
                        {new Date(item.at).toLocaleString("vi-VN")}
                      </summary>
                      <div className="timeline__diff">
                        <div>
                          <h3>Trước</h3>
                          <pre className="text-block text-block--scroll">
                            {JSON.stringify(item.before, null, 2)}
                          </pre>
                        </div>
                        <div>
                          <h3>Sau</h3>
                          <pre className="text-block text-block--scroll">
                            {JSON.stringify(item.after, null, 2)}
                          </pre>
                        </div>
                      </div>
                    </details>
                  </li>
                ))}
              </ol>
            )}
            <div className="btn-row">
              <Button
                variant="secondary"
                size="sm"
                disabled={!before}
                onClick={() => onBefore(undefined)}
              >
                Lịch sử mới nhất
              </Button>
              <Button
                variant="secondary"
                size="sm"
                disabled={!history.data.length}
                onClick={() => onBefore(history.data.at(-1)?.id)}
              >
                Lịch sử cũ hơn
              </Button>
            </div>
          </>
        )}
      </div>
    </section>
  );
}
