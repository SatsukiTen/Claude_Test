from src.hello import greet, add


def test_greet():
    assert greet("Claude") == "Hello, Claude!"


def test_add():
    assert add(1, 2) == 3
    assert add(-1, 1) == 0
