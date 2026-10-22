#!/bin/sh
set -eu
cd /tmp
curl -4 -fL https://github.com/tesseract-ocr/tesseract/archive/refs/tags/5.5.3.tar.gz -o tesseract.tar.gz
sha256sum tesseract.tar.gz > /report/source.sha256
tar -xzf tesseract.tar.gz
cmake -S /tmp/tesseract-5.5.3 -B /tmp/tess-build -DCMAKE_BUILD_TYPE=Release -DBUILD_TRAINING_TOOLS=OFF -DBUILD_TESTS=OFF -DCMAKE_EXPORT_COMPILE_COMMANDS=ON > /report/cmake.log 2>&1
c++ --version > /report/compiler.txt
c++ -v >> /report/compiler.txt 2>&1
cmake --version > /report/cmake-version.txt
cp /tmp/tess-build/compile_commands.json /report/compile_commands.json
cp /tmp/tess-build/CMakeCache.txt /report/CMakeCache.txt
mkdir -p /report/source
cp /tmp/tesseract-5.5.3/src/ccutil/helpers.h /tmp/tesseract-5.5.3/src/ccutil/serialis.h /tmp/tesseract-5.5.3/src/ccutil/serialis.cpp /report/source/
python3 - <<'PY'
import json,subprocess,pathlib
entries=json.loads(pathlib.Path('/report/compile_commands.json').read_text())
e=next(x for x in entries if x['file'].endswith('/serialis.cpp'))
pathlib.Path('/report/serialis-command.txt').write_text(e['command']+'\n')
with open('/report/serialis-diagnostic.log','wb') as f:
    result=subprocess.run(e['command'],shell=True,cwd=e['directory'],stdout=f,stderr=subprocess.STDOUT)
pathlib.Path('/report/compile-exit-code.txt').write_text(str(result.returncode)+'\n')
raise SystemExit(result.returncode)
PY
