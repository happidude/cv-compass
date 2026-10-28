/** Synthetic three-page PDF; source text is never real CV data. */
export function m11Pdf() {
  const objects: string[] = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R 5 0 R 7 0 R] /Count 3 >>",
  ];
  for (let page = 1; page <= 3; page++) {
    const id = 3 + (page - 1) * 2,
      stream = id + 1;
    const text =
      "BT /F1 12 Tf 50 740 Td (Synthetic PDF page " + page + ") Tj ET";
    objects.push(
      "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 9 0 R >> >> /Contents " +
        stream +
        " 0 R >>",
      "<< /Length " + text.length + " >>\nstream\n" + text + "\nendstream",
    );
  }
  objects.push("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
  let pdf = "%PDF-1.4\n";
  const offsets: number[] = [];
  objects.forEach((value, index) => {
    offsets.push(pdf.length);
    pdf += index + 1 + " 0 obj\n" + value + "\nendobj\n";
  });
  const xref = pdf.length;
  pdf += "xref\n0 10\n0000000000 65535 f \n";
  for (const offset of offsets)
    pdf += String(offset).padStart(10, "0") + " 00000 n \n";
  return Buffer.from(
    pdf + "trailer\n<< /Size 10 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF",
  );
}
