// Synthetic candidate data for the dev mock only. Never real CV data.
import { http, HttpResponse, delay } from "msw";
import { config } from "../shared/config";
import { timestamp } from "./fixtures";
const base = config.apiBase;
const span = (page: number, text: string) => ({
  extractionId: 31,
  page,
  start: 0,
  end: text.length,
  text,
});
const personalInfo = {
  schemaVersion: 1,
  fields: {
    fullName: {
      raw: "SYNTHETIC AN NGUYEN",
      normalized: "Synthetic An Nguyen",
      state: "PROPOSED",
      evidence: [span(1, "SYNTHETIC AN NGUYEN")],
      documentId: 7,
      origin: "MAPPING",
    },
    email: {
      raw: "an.synthetic@example.invalid",
      state: "PROPOSED",
      evidence: [span(1, "an.synthetic@example.invalid")],
      documentId: 7,
      origin: "MAPPING",
    },
    phone: { state: "NOT_FOUND", documentId: 7, origin: "MAPPING" },
    location: {
      raw: "Hà Nội / TP.HCM",
      state: "AMBIGUOUS",
      alternatives: [
        { raw: "Hà Nội", normalized: null, evidence: [span(1, "Hà Nội")] },
        { raw: "TP.HCM", normalized: null, evidence: [span(2, "TP.HCM")] },
      ],
      issues: [{ code: "MULTIPLE_LOCATIONS", field: "location" }],
      documentId: 7,
      origin: "MAPPING",
    },
  },
};
const experience = {
  company: {
    raw: "Synthetic Logistics JSC",
    industryCodes: ["LOGISTICS"],
    evidence: [span(2, "Synthetic Logistics JSC")],
  },
  title: {
    raw: "Senior Backend Engineer",
    functionCodes: ["ENGINEERING"],
    seniorityCode: "SENIOR",
  },
  period: {
    start: { year: 2021, month: 3, day: null, precision: "MONTH" },
    end: null,
    ongoingAsStated: true,
    state: "PROPOSED",
  },
  descriptionRaw:
    "Synthetic text: xây dựng dịch vụ đặt chỗ, tối ưu truy vấn, hướng dẫn 3 thành viên.",
  evidence: [span(2, "Synthetic Logistics JSC — Senior Backend Engineer")],
  issues: [],
};
const experienceId = "11111111-1111-4111-8111-111111111111";
let profile = {
  id: 1,
  version: 3,
  basic: {
    fullName: "Synthetic An Nguyen",
    email: "an.synthetic@example.invalid",
    location: "Hà Nội / TP.HCM",
  },
  basicState: "UNVERIFIED",
  basicOrigin: "MAPPING",
  extensions: { personalInfo },
  experiences: [
    {
      id: experienceId,
      state: "UNVERIFIED",
      origin: "MAPPING",
      documentId: 7,
      resultId: 10,
      data: experience,
    },
  ],
  documents: [{ id: 7, filename: "Synthetic_scan.pdf", importedAt: timestamp }],
  experienceSummary: {
    totalMonths: 67,
    countedIds: [experienceId],
    skipped: [],
    ongoingReferenceDates: [timestamp.slice(0, 10)],
    hasYearOnlyPeriods: false,
  },
};
const proposals = [
  {
    id: "22222222-2222-4222-8222-222222222222",
    documentId: 7,
    resultId: 10,
    data: {
      ...experience,
      company: { raw: "Synthetic Retail Co.", industryCodes: ["RETAIL"] },
      title: { raw: "Backend Engineer", functionCodes: ["ENGINEERING"] },
      period: {
        start: { year: 2018, month: null, day: null, precision: "YEAR" },
        end: { year: 2021, month: null, day: null, precision: "YEAR" },
        ongoingAsStated: false,
        state: "PROPOSED",
      },
      evidence: [span(3, "Synthetic Retail Co. 2018 – 2021")],
    },
  },
];
export const candidatePage = {
  content: [
    {
      id: 4,
      version: 1,
      fullName: null,
      basicState: "UNVERIFIED",
      importedAt: timestamp,
    },
    {
      id: 3,
      version: 2,
      fullName: "Synthetic Bình Trần",
      basicState: "EDITED",
      importedAt: timestamp,
    },
    {
      id: 2,
      version: 5,
      fullName: "Synthetic Chi Lê",
      basicState: "CONFIRMED",
      importedAt: timestamp,
    },
    {
      id: 1,
      version: 3,
      fullName: "Synthetic An Nguyen",
      basicState: "UNVERIFIED",
      importedAt: timestamp,
    },
  ],
  hasNext: false,
  nextBeforeId: null,
};
/** Minimal three-page synthetic PDF so the source pane renders in dev. */
function syntheticPdf() {
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R 5 0 R 7 0 R] /Count 3 >>",
  ];
  for (let page = 1; page <= 3; page++) {
    const stream = 4 + (page - 1) * 2;
    const text = `BT /F1 14 Tf 60 760 Td (Synthetic CV page ${page}) Tj ET`;
    objects.push(
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 9 0 R >> >> /Contents ${stream} 0 R >>`,
      `<< /Length ${text.length} >>\nstream\n${text}\nendstream`,
    );
  }
  objects.push("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
  let pdf = "%PDF-1.4\n";
  const offsets: number[] = [];
  objects.forEach((value, index) => {
    offsets.push(pdf.length);
    pdf += `${index + 1} 0 obj\n${value}\nendobj\n`;
  });
  const xref = pdf.length;
  pdf += "xref\n0 10\n0000000000 65535 f \n";
  for (const offset of offsets)
    pdf += `${String(offset).padStart(10, "0")} 00000 n \n`;
  pdf += `trailer\n<< /Size 10 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  return new TextEncoder().encode(pdf);
}
const notFound = () =>
  HttpResponse.json(
    {
      code: "NOT_FOUND",
      message: "synthetic",
      correlationId: null,
      details: [],
    },
    { status: 404 },
  );
export const candidateHandlers = [
  http.get(`${base}/candidates/search/options`, () =>
    HttpResponse.json(candidateSearchOptions),
  ),
  http.get(`${base}/candidates/search/companies`, async ({ request }) => {
    await delay(80);
    const q = new URL(request.url).searchParams.get("q")?.trim().toLowerCase();
    return HttpResponse.json(
      q
        ? candidateCompanySuggestions.filter((company) =>
            company.name.toLowerCase().includes(q),
          )
        : [],
    );
  }),
  http.get(`${base}/candidates/search`, async ({ request }) => {
    await delay(100);
    const params = new URL(request.url).searchParams;
    const companyId = params.get("companyId");
    if (
      params.has("companyId") &&
      (params.has("company") ||
        params.getAll("companyId").length !== 1 ||
        !/^\d+$/.test(companyId ?? "") ||
        BigInt(companyId!) <= 0n ||
        BigInt(companyId!) > 9223372036854775807n)
    )
      return HttpResponse.json(
        {
          code: "VALIDATION_ERROR",
          message: "Use either company or one positive companyId.",
          correlationId: null,
          details: [{ field: "companyId" }],
        },
        { status: 400 },
      );
    const companyName = params.get("company")?.trim().toLowerCase();
    function matchesCompany(row: { id: number }) {
      if (companyId === null && !companyName) return true;
      return (candidateSearchCompanyLinks[row.id] ?? []).some((id) => {
        const company = candidateCompanySuggestions.find(
          (item) => item.id === id,
        )!;
        const parent = candidateCompanySuggestions.find(
          (item) => item.name === company.parentName,
        );
        return companyId !== null
          ? [company.id, parent?.id].some(
              (id) => id !== undefined && BigInt(id) === BigInt(companyId),
            )
          : [company.name, company.parentName].some((name) =>
              name?.toLowerCase().includes(companyName!),
            );
      });
    }
    const nextContent = candidateSearchNextPage.content.filter(matchesCompany);
    if (params.has("cursor"))
      return HttpResponse.json({
        ...candidateSearchNextPage,
        content: nextContent,
      });
    const hasNext = nextContent.length > 0;
    return HttpResponse.json({
      ...candidateSearchPage,
      content: candidateSearchPage.content.filter(matchesCompany),
      hasNext,
      nextCursor: hasNext ? candidateSearchPage.nextCursor : null,
    });
  }),
  http.get(`${base}/candidates`, async () => {
    await delay(150);
    return HttpResponse.json(candidatePage);
  }),
  http.get(`${base}/candidates/:id`, async ({ params }) => {
    await delay(150);
    return params.id === "1" ? HttpResponse.json(profile) : notFound();
  }),
  http.get(`${base}/candidates/:id/proposals`, () =>
    HttpResponse.json(proposals),
  ),
  http.get(`${base}/candidates/:id/history`, () =>
    HttpResponse.json([
      {
        id: 3,
        version: profile.version,
        actor: "synthetic-user",
        action: "PUBLISH",
        before: {},
        after: { basicState: "UNVERIFIED" },
        at: timestamp,
      },
    ]),
  ),
  // Dev-only writes: bump the version so the UI exercises its success path.
  http.all(`${base}/candidates/:id/*`, ({ request }) => {
    if (request.method === "GET") return undefined;
    profile = { ...profile, version: profile.version + 1 };
    return HttpResponse.json(profile);
  }),
  http.get(`${base}/documents/:id/content`, () =>
    HttpResponse.arrayBuffer(syntheticPdf().buffer as ArrayBuffer, {
      headers: { "Content-Type": "application/pdf" },
    }),
  ),
];

export const candidateSearchOptions = {
  FUNCTION: [
    { code: "IT", label: "Công nghệ thông tin" },
    { code: "ENGINEERING", label: "Kỹ thuật" },
  ],
  SENIORITY: [
    { code: "SENIOR", label: "Chuyên viên cao cấp" },
    { code: "MANAGER", label: "Quản lý" },
  ],
  SCOPE: [
    { code: "COMPANY", label: "Toàn công ty/tập đoàn" },
    { code: "REGION", label: "Vùng/khu vực" },
  ],
  INDUSTRY: [
    { code: "LOGISTICS", label: "Logistics / vận tải / phân phối" },
    { code: "MANUFACTURING", label: "Sản xuất công nghiệp" },
  ],
  provinces: ["Hà Nội", "Hồ Chí Minh"],
};
export const candidateCompanySuggestions = [
  { id: 22, name: "Synthetic Logistics", parentName: "Synthetic Group" },
  { id: 23, name: "Synthetic Group", parentName: null },
  { id: 24, name: "1080", parentName: null },
];
// Includes past employers even when the current company in the result is null.
const candidateSearchCompanyLinks: Record<number, number[]> = {
  1: [22],
  8: [24],
  3: [22],
};
export const candidateSearchPage = {
  content: [
    {
      id: 1,
      fullName: "Synthetic An Nguyen",
      currentTitle: { id: 11, name: "Kỹ sư phần mềm", aiNormalized: true },
      currentCompany: { ...candidateCompanySuggestions[0], aiNormalized: true },
      province: "Hà Nội",
      totalExperienceMonths: 59,
      experienceEstimated: true,
      score: 640,
      matchReasons: ["COMPANY_PARENT", "COMPANY_CURRENT", "TITLE_CATALOG"],
      matchedCompany: { id: 23, name: "Synthetic Group" },
    },
    {
      id: 8,
      fullName: "Synthetic Binh Tran",
      currentTitle: {
        id: 12,
        name: "Chuyên viên kỹ thuật",
        aiNormalized: false,
      },
      currentCompany: null,
      province: "Hồ Chí Minh",
      totalExperienceMonths: 8,
      experienceEstimated: true,
      score: 200,
      matchReasons: ["TITLE_CATALOG", "KEYWORD_FUZZY"],
      matchedCompany: null,
    },
  ],
  nextCursor: "NjQwLjAwMDAwMHw4",
  hasNext: true,
};
export const candidateSearchNextPage = {
  content: [
    {
      id: 3,
      fullName: null,
      currentTitle: null,
      currentCompany: null,
      province: null,
      totalExperienceMonths: null,
      experienceEstimated: true,
      score: 10,
      matchReasons: ["KEYWORD_TEXT", "FUTURE_REASON"],
      matchedCompany: null,
    },
  ],
  nextCursor: null,
  hasNext: false,
};
