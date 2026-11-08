import { EvidenceButton } from "./evidence";
import { dateLabel, periodStateLabel } from "./labels";
import type { ExperienceData } from "./schema";
export type Evidence = NonNullable<ExperienceData["evidence"]>[number];
/** Read-only view of one experience value set (current or proposed). */
export function ExperienceValues({
  data,
  documentId,
  onEvidence,
}: {
  data: ExperienceData;
  documentId: string | null;
  onEvidence: (doc: string, evidence: Evidence) => void;
}) {
  return (
    <div className="xp">
      <div className="xp__head">
        <h3 className="xp__company">
          <span>{data.company.raw || "Công ty chưa xác định"}</span>
        </h3>
        <p className="xp__title">
          <span>{data.title.raw || "Chức danh chưa xác định"}</span>
        </p>
      </div>
      <p className="xp__period">
        {dateLabel(data.period.start)} →{" "}
        {data.period.ongoingAsStated
          ? "Đang làm theo CV"
          : dateLabel(data.period.end)}{" "}
        · {periodStateLabel(data.period.state)}
      </p>
      <dl className="xp__codes">
        <div>
          <dt>Chuyên môn</dt>
          <dd>{data.title.functionCodes?.join(", ") || "Chưa ánh xạ"}</dd>
        </div>
        <div>
          <dt>Cấp bậc</dt>
          <dd>{data.title.seniorityCode || "Chưa ánh xạ"}</dd>
        </div>
        <div>
          <dt>Ngành doanh nghiệp</dt>
          <dd>
            {data.company.industryCodes?.join(", ") || "Chưa ánh xạ"}
            {data.company.industryBasis && " — " + data.company.industryBasis}
          </dd>
        </div>
      </dl>
      {data.descriptionRaw && (
        <pre className="text-block text-block--scroll">
          {data.descriptionRaw}
        </pre>
      )}
      {data.issues && data.issues.length > 0 && (
        <p className="field-row__issue">
          Vấn đề: {data.issues.map((issue) => issue.code).join(", ")}
        </p>
      )}
      {documentId && !!data.evidence?.length && (
        <div className="ref-row">
          {data.evidence.map((span, index) => (
            <EvidenceButton
              key={index}
              page={span.page}
              index={index + 1}
              label={`Đối chiếu trang ${span.page}, đoạn ${index + 1}`}
              onClick={() => onEvidence(documentId, span)}
            />
          ))}
        </div>
      )}
      <details className="disclosure">
        <summary>Phương án khác và bằng chứng nguồn</summary>
        <pre className="text-block text-block--scroll">
          {JSON.stringify(data, null, 2)}
        </pre>
      </details>
    </div>
  );
}
