package com.cvcompass.backend;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class M11SyntheticPdf {
    private M11SyntheticPdf() {}

    static byte[] create() {
        String content =
                "BT /F1 12 Tf 50 740 Td (WORK EXPERIENCE) Tj 0 -20 Td (Employer: Northstar Devices) Tj 0 -20 Td (Title: Engineering Manager) Tj 0 -20 Td (Dates: 2018 - 2021) Tj 0 -20 Td (Partner: Synthetic Client) Tj ET";
        var objects =
                List.of(
                        "<< /Type /Catalog /Pages 2 0 R >>",
                        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
                        "<< /Length "
                                + content.length()
                                + " >>\nstream\n"
                                + content
                                + "\nendstream",
                        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        var pdf = new StringBuilder("%PDF-1.4\n");
        var offsets = new ArrayList<Integer>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 6\n0000000000 65535 f \n");
        for (int offset : offsets) pdf.append("%010d 00000 n \n".formatted(offset));
        pdf.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n")
                .append(xref)
                .append("\n%%EOF");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
