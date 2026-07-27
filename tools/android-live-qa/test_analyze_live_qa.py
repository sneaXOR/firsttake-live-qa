import importlib.util
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("analyze_live_qa.py")
SPEC = importlib.util.spec_from_file_location("analyze_live_qa", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def test_percentile95_uses_observed_upper_tail():
    assert MODULE.percentile95([1.0, 2.0, 3.0, 4.0]) == 4.0


def test_percentile95_is_not_assessable_without_values():
    assert MODULE.percentile95([]) is None
