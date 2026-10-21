#!/bin/sh
set -eu
c++ --version > /report/compiler.txt
c++ -v >> /report/compiler.txt 2>&1
printf '%s\n' 'c++ -O3 -DNDEBUG -std=gnu++20 -c /reproducer.cpp -o /tmp/reproducer.o' > /report/commands.txt
c++ -O3 -DNDEBUG -std=gnu++20 -c /reproducer.cpp -o /tmp/reproducer.o > /report/reproducer-diagnostic.log 2>&1
c++ -O3 -DNDEBUG -std=gnu++20 -S /reproducer.cpp -o /report/reproducer.s >> /report/reproducer-diagnostic.log 2>&1
printf '%s\n' 'c++ -O3 -DNDEBUG -std=gnu++20 -fsanitize=address,undefined -fno-omit-frame-pointer /reproducer.cpp -o /tmp/reproducer-asan' >> /report/commands.txt
c++ -O3 -DNDEBUG -std=gnu++20 -fsanitize=address,undefined -fno-omit-frame-pointer /reproducer.cpp -o /tmp/reproducer-asan > /report/sanitizer-build.log 2>&1
/tmp/reproducer-asan > /report/sanitizer-run.log 2>&1
printf '%s\n' '517 bounded cases; exit 0; sanitizer scope=standalone reduced path only' > /report/result.txt
