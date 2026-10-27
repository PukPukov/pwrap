# Historical differential fixtures

`pure-cases.json` contains269 calls captured from the original209-test Python
suite before its removal. Intermediate commit `62b1ae0` preserves both the exact
Python implementation and the generator (`home/code/test/generate_reference_cases.py`).
The fixtures are data, not another implementation, and do not need Python to run.
They supplement the direct Java/native tests;269 oracle calls are not269 direct ports.
