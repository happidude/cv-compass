import { Notice } from "../../shared/ui";
import { experienceSkipReasonLabel, formatExperienceMonths } from "./labels";
import type { ExperienceSummary, Profile } from "./schema";

export function ExperienceSummaryNotice({
  summary,
  experiences,
}: {
  summary: ExperienceSummary;
  experiences: Profile["experiences"];
}) {
  const experiencesById = new Map(experiences.map((row) => [row.id, row.data]));
  const referenceDates = summary.ongoingReferenceDates
    .map((date) => date.split("-").reverse().join("/"))
    .join(", ");
  return (
    <Notice
      tone={
        summary.hasYearOnlyPeriods || summary.skipped.length ? "warn" : "info"
      }
    >
      <p>
        <strong>
          Tổng kinh nghiệm: {formatExperienceMonths(summary.totalMonths)}
        </strong>
        {" · ước tính"}
        {referenceDates && <> · tính đến {referenceDates}</>}
      </p>
      {summary.hasYearOnlyPeriods && (
        <p>Có kỳ chỉ ghi năm nên tổng kinh nghiệm có thể chênh lệch.</p>
      )}
      {summary.totalMonths === 0 &&
        (summary.countedIds.length > 0 || summary.skipped.length === 0) && (
          <p>
            {summary.countedIds.length > 0
              ? "Các kỳ được tính chưa đủ một tháng."
              : "Chưa có kỳ kinh nghiệm đủ thông tin."}
          </p>
        )}
      {summary.skipped.length > 0 && (
        <>
          <p>Kinh nghiệm chưa tính vào tổng</p>
          <ul className="experience-summary__skipped">
            {summary.skipped.map(({ id, reason }) => {
              const row = experiencesById.get(id);
              return (
                <li key={id}>
                  {row?.company.raw || "Công ty chưa xác định"}
                  {row?.title.raw && <> · {row.title.raw}</>}
                  {" · "}
                  {experienceSkipReasonLabel(reason)}
                </li>
              );
            })}
          </ul>
        </>
      )}
    </Notice>
  );
}
