"""Frozen extraction records and strict JSON response schemas."""

from dataclasses import asdict, dataclass


@dataclass(frozen=True)
class Evidence:
    page: int
    start: int
    end: int
    text: str


@dataclass(frozen=True)
class FieldValue:
    key: str
    value: str
    evidence: Evidence
    additional_evidence: tuple[Evidence, ...] = ()


@dataclass(frozen=True)
class Experience:
    title: str | None
    company: str | None
    start: str | None
    end: str | None
    ongoing: bool
    evidence: dict[str, Evidence]


@dataclass(frozen=True)
class Issue:
    code: str
    field: str | None = None


@dataclass(frozen=True)
class ExtractionResult:
    fields: tuple[FieldValue, ...]
    experiences: tuple[Experience, ...]
    issues: tuple[Issue, ...]
    prompt_version: str
    provider_name: str

    def to_dict(self) -> dict:
        """Return extraction records as a JSON-serializable structure."""
        return asdict(self)


def object_schema(properties: dict) -> dict:
    """Build a strict object schema requiring every declared key."""
    return {
        'type': 'object',
        'properties': properties,
        'required': list(properties),
        'additionalProperties': False,
    }


NULL_STRING = {'type': ['string', 'null']}
PERSONAL_KEYS = ('fullName', 'gender', 'location', 'dateOfBirth', 'summary')
WHOLE_PERSONAL_SCHEMA = object_schema(
    {
        'personal': object_schema(
            {
                key: {
                    'anyOf': [
                        object_schema(
                            {
                                'value': (
                                    {'type': 'string', 'enum': ['MALE', 'FEMALE', 'OTHER']}
                                    if key == 'gender'
                                    else {'type': 'string'}
                                ),
                                'quote': {'type': 'string'},
                            }
                        ),
                        {'type': 'null'},
                    ]
                }
                for key in PERSONAL_KEYS
            }
        )
    }
)
PERSONAL_SCHEMA = object_schema(
    {
        'personal': object_schema(
            {
                **WHOLE_PERSONAL_SCHEMA['properties']['personal']['properties'],
                'summary': {
                    'anyOf': [
                        {
                            'type': 'array',
                            'minItems': 1,
                            'maxItems': 8,
                            'items': object_schema(
                                {
                                    'quoteStart': {'type': 'string'},
                                    'quoteEnd': {'type': 'string'},
                                }
                            ),
                        },
                        {'type': 'null'},
                    ]
                },
            }
        )
    }
)
EXPERIENCE_SCHEMA = object_schema(
    {
        'experiences': {
            'type': 'array',
            'items': object_schema(
                {
                    'title': NULL_STRING,
                    'company': NULL_STRING,
                    'start': NULL_STRING,
                    'end': NULL_STRING,
                    'ongoing': {'type': 'boolean'},
                    'quotes': object_schema(
                        {key: NULL_STRING for key in ('title', 'company', 'period')}
                    ),
                }
            ),
        }
    }
)
WHOLE_SCHEMA = object_schema(
    {
        'personal': WHOLE_PERSONAL_SCHEMA['properties']['personal'],
        'experiences': EXPERIENCE_SCHEMA['properties']['experiences'],
    }
)

NULL_LINE = {'type': ['integer', 'null']}
LINE_TEXT_SCHEMA = object_schema({'line': NULL_LINE, 'text': NULL_STRING})
LINE_VALUE_SCHEMA = object_schema({'line': NULL_LINE, 'value': NULL_STRING})
LINES_EXPERIENCE_SCHEMA = object_schema(
    {
        'experiences': {
            'type': 'array',
            'items': object_schema(
                {
                    'title': LINE_VALUE_SCHEMA,
                    'company': LINE_VALUE_SCHEMA,
                    'period': LINE_TEXT_SCHEMA,
                    'ongoing': {'type': 'boolean'},
                }
            ),
        }
    }
)
LINES_PERSONAL_SCHEMA = object_schema(
    {
        'personal': object_schema(
            {
                'fullName': LINE_VALUE_SCHEMA,
                'location': object_schema(
                    {'line': NULL_LINE, 'endLine': NULL_LINE, 'value': NULL_STRING}
                ),
                'gender': object_schema(
                    {
                        'line': NULL_LINE,
                        'value': {
                            'anyOf': [
                                {'type': 'string', 'enum': ['MALE', 'FEMALE', 'OTHER']},
                                {'type': 'null'},
                            ]
                        },
                    }
                ),
                'dateOfBirth': LINE_VALUE_SCHEMA,
                'summary': {
                    'type': 'array',
                    'maxItems': 8,
                    'items': object_schema(
                        {'startLine': {'type': 'integer'}, 'endLine': {'type': 'integer'}}
                    ),
                },
            }
        )
    }
)
