"""Offline aggregate measurement for personal-info annotations; no parser or network calls."""

import argparse
import json
import sys

from personal_info_gold import FIELDS, LABELS, InputError, canonical, load, require, validate


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        self.exit(2, 'EVALUATION_INPUT_REJECTED\n')


def _counts() -> dict[str, int]:
    return dict.fromkeys((
        'documents', 'enabled', 'disabled', 'excluded_gold', 'evaluated', 'gold', 'predictions',
        'tp', 'fp', 'fn', 'missing', 'ambiguous', 'invalid', 'wrong_reference', 'wrong_company',
        'wrong_recruiter', 'unknown_subject', 'evidence_total', 'evidence_valid',
        'evidence_invalid', 'evidence_unverifiable',
    ), 0)


def _evidence_counts(source: dict | None, prediction: dict, counts: dict) -> None:
    for span in prediction['evidence']:
        counts['evidence_total'] += 1
        if source is None:
            counts['evidence_unverifiable'] += 1
            continue
        raw = next((page['rawText'] for page in source['pages']
                    if page['page'] == span['page']), None)
        exact = (span['extractionId'] == source['extractionId']
                 and raw is not None and span['end'] <= len(raw)
                 and raw[span['start']:span['end']] == span['text'])
        if not exact:
            counts['evidence_invalid'] += 1
        elif span.get('subject', 'UNKNOWN') == 'UNKNOWN' or span.get('supportsValue') is None:
            counts['evidence_unverifiable'] += 1
        else:
            # Ownership/support are independent per-span audits, never copied from parser claims.
            valid = span['subject'] == 'CANDIDATE' and span['supportsValue']
            counts['evidence_valid' if valid else 'evidence_invalid'] += 1


def _field_counts(field: str, gold: dict, prediction: dict, source: dict | None) -> dict:
    counts = _counts()
    counts['documents'] = 1
    counts['enabled' if prediction['enabled'] else 'disabled'] = 1
    counts['excluded_gold'] = int(gold['status'] != 'ANNOTATED')
    if not prediction['enabled'] or counts['excluded_gold']:
        return counts
    counts['evaluated'] = 1
    values = gold['acceptedValues']
    counts['gold'] = int(bool(values))
    state = prediction['state']
    if state != 'PROPOSED':
        counts[{'NOT_FOUND': 'missing', 'AMBIGUOUS': 'ambiguous', 'INVALID': 'invalid'}[state]] = 1
        counts['fn'] = int(bool(values))
    else:
        counts['predictions'] = 1
        subject = prediction['subject']
        equal = (subject == 'CANDIDATE'
                 and any(canonical(field, prediction['value']) == canonical(field, value)
                         for value in values))
        counts['tp'] = int(equal)
        counts['fp'] = int(not equal)
        counts['fn'] = int(bool(values) and not equal)
        if subject != 'CANDIDATE':
            counts[{'REFERENCE': 'wrong_reference', 'COMPANY': 'wrong_company',
                    'RECRUITER': 'wrong_recruiter', 'UNKNOWN': 'unknown_subject'}[subject]] = 1
    _evidence_counts(source, prediction, counts)
    return counts


def _aggregate(rows: list[dict]) -> dict:
    counts = _counts()
    for row in rows:
        for key in counts:
            counts[key] += row[key]
    tp, fp, fn = counts['tp'], counts['fp'], counts['fn']
    return counts | {'precision': tp / (tp + fp) if tp + fp else 'N/A',
                     'recall': tp / (tp + fn) if tp + fn else 'N/A'}


def evaluate(gold: object, predictions: object, min_group_size: int = 2) -> dict:
    """Measure confirmed gold only; emit allowlisted labels and aggregates, never input text."""
    require(type(min_group_size) is int and min_group_size >= 2)
    gold_docs, pred_docs = validate(gold, True), validate(predictions, False)
    require(set(gold_docs) == set(pred_docs))
    rows = {key: {field: _field_counts(field, doc['fields'][field],
                                     pred_docs[key]['fields'][field], doc.get('source'))
                  for field in FIELDS} for key, doc in gold_docs.items()}
    report = {
        'schemaVersion': 1,
        'documents': len(rows),
        'minGroupSize': min_group_size,
        'fields': {field: _aggregate([row[field] for row in rows.values()]) for field in FIELDS},
        'breakdown': {},
    }
    for dimension, labels in LABELS.items():
        groups = {}
        for label in sorted(labels):
            members = [key for key, doc in gold_docs.items() if doc[dimension] == label]
            if len(members) >= min_group_size:
                groups[label] = {'documents': len(members), 'fields': {
                    field: _aggregate([rows[key][field] for key in members]) for field in FIELDS}}
        report['breakdown'][dimension] = groups
    return report


def main(argv: list[str] | None = None) -> int:
    parser = SafeArgumentParser(description='Aggregate confirmed local personal-info gold.')
    parser.add_argument('--gold', required=True)
    parser.add_argument('--predictions', required=True)
    parser.add_argument('--min-group-size', type=int, default=2)
    args = parser.parse_args(argv)
    try:
        result = evaluate(load(args.gold), load(args.predictions), args.min_group_size)
    except (InputError, TypeError, KeyError, RecursionError):
        print('EVALUATION_INPUT_REJECTED', file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=True, sort_keys=True, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
