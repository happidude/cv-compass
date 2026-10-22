"""Safe build verification inventory; no PDF text/input paths/credentials."""
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import sys


def digest(path):
    with Path(path).open('rb') as stream: return hashlib.file_digest(stream,'sha256').hexdigest()


packages={d.metadata['Name']:{'version':d.version,'inside_clean_prefix':Path(d.locate_file('')).resolve().is_relative_to(Path(sys.prefix).resolve())} for d in importlib.metadata.distributions()}
assert all(item['inside_clean_prefix'] for item in packages.values()),'Inherited package distribution detected'
models=Path(os.environ['OCR_TEST_MODELS'])
report={'python':platform.python_version(),'platform':platform.platform(),'sys_prefix':sys.prefix,
        'base_prefix':sys.base_prefix,'sys_path':sys.path,'packages':packages,
        'binary_sha256':digest(os.environ['OCR_TEST_TESSERACT']),
        'models_sha256':{name:digest(models/(name+'.traineddata')) for name in ('eng','vie','osd')},
        'not_an_OCR_result':True}
print(json.dumps(report,indent=2))
