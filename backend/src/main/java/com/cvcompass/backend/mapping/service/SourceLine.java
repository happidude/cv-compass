package com.cvcompass.backend.mapping.service;

import com.cvcompass.backend.ingestion.api.OcrExtractionReader.Page;
import com.cvcompass.backend.mapping.dto.MappingResult.Evidence;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** NFC/whitespace-derived text with each UTF-16 unit mapped back to its whole raw grapheme. */
final class SourceLine {
    final String text;
    final int page;
    final String raw;
    private final String pageRaw;
    private final int offset;
    private final int[] starts;
    private final int[] ends;
    private final long extractionId;

    SourceLine(long extractionId, Page page, int offset, String raw) {
        this.extractionId = extractionId;
        this.page = page.number();
        this.raw = raw;
        this.pageRaw = page.rawText();
        this.offset = offset;
        var normalized = new StringBuilder();
        var from = new ArrayList<Integer>();
        var to = new ArrayList<Integer>();
        var iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(raw);
        int begin = iterator.first();
        for (int end = iterator.next();
                end != BreakIterator.DONE;
                begin = end, end = iterator.next()) {
            String value = Normalizer.normalize(raw.substring(begin, end), Normalizer.Form.NFC);
            if (value.codePoints()
                    .allMatch(cp -> Character.isWhitespace(cp) || Character.isSpaceChar(cp))) {
                if (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) != ' ') {
                    normalized.append(' ');
                    from.add(begin);
                    to.add(end);
                } else if (!normalized.isEmpty()) to.set(to.size() - 1, end);
            } else {
                normalized.append(value);
                for (int i = 0; i < value.length(); i++) {
                    from.add(begin);
                    to.add(end);
                }
            }
        }
        if (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == ' ') {
            normalized.setLength(normalized.length() - 1);
            from.removeLast();
            to.removeLast();
        }
        this.text = normalized.toString();
        this.starts = from.stream().mapToInt(Integer::intValue).toArray();
        this.ends = to.stream().mapToInt(Integer::intValue).toArray();
    }

    Evidence span(int start, int end) {
        if (start < 0 || end <= start || end > text.length())
            throw new IllegalArgumentException("Invalid normalized span");
        int rawStart = offset + starts[start], rawEnd = offset + ends[end - 1];
        String quote = pageRaw.substring(rawStart, rawEnd);
        return new Evidence(
                extractionId,
                page,
                pageRaw.codePointCount(0, rawStart),
                pageRaw.codePointCount(0, rawEnd),
                quote);
    }

    Evidence whole() {
        return span(0, text.length());
    }

    Evidence rawWhole() {
        return new Evidence(
                extractionId,
                page,
                pageRaw.codePointCount(0, offset),
                pageRaw.codePointCount(0, offset + raw.length()),
                raw);
    }

    static List<SourceLine> read(long extractionId, List<Page> pages) {
        var lines = new ArrayList<SourceLine>();
        for (var page : pages) {
            if (!"ok".equals(page.status())) continue;
            int start = 0;
            String raw = page.rawText();
            for (int i = 0; i <= raw.length(); i++) {
                if (i == raw.length() || raw.charAt(i) == '\n' || raw.charAt(i) == '\r') {
                    var line = new SourceLine(extractionId, page, start, raw.substring(start, i));
                    if (!line.text.isEmpty()) lines.add(line);
                    if (lines.size() > 10000)
                        throw new IllegalArgumentException("Experience line limit exceeded");
                    if (i < raw.length()
                            && raw.charAt(i) == '\r'
                            && i + 1 < raw.length()
                            && raw.charAt(i + 1) == '\n') i++;
                    start = i + 1;
                }
            }
        }
        return List.copyOf(lines);
    }
}
