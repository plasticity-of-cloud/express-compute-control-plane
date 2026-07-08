"""
Robot Framework library that wraps MockServer for use in .robot test suites.

Usage in .robot file::

    Library    ../libraries/MockServerLibrary.py

    Start Mock Server
    ${PORT}=    Get Mock Server Port
    Set Response    GET    /clusters/my-cluster    200    {"state":"ready","managed":true}
    ...
    ${count}=    Get Request Count    GET    /clusters/my-cluster
    Should Be Equal As Integers    ${count}    1
    Stop Mock Server
"""
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from mock_server.server import MockServer


class MockServerLibrary:
    """Robot Framework library wrapping the in-process MockServer."""

    ROBOT_LIBRARY_SCOPE = "SUITE"

    def __init__(self):
        self._server = MockServer()
        self._port: int = 0

    def start_mock_server(self) -> int:
        """Start the mock server on a random free port. Returns the port number."""
        self._port = self._server.start()
        return self._port

    def stop_mock_server(self):
        """Stop the mock server."""
        self._server.stop()

    def get_mock_server_port(self) -> int:
        """Return the port the mock server is listening on."""
        return self._port

    def set_response(self, method: str, path_pattern: str, status_code: int, body: str):
        """
        Register a response for HTTP method + path regex pattern.

        Example::
            Set Response    GET    /clusters/my-cluster    200    {"state":"ready"}
        """
        self._server.set_response(method, path_pattern, int(status_code), body)

    def reset_responses(self):
        """Remove all registered mock responses."""
        self._server.reset_responses()

    def clear_requests(self):
        """Clear the recorded request history."""
        self._server.clear_requests()

    def reset_mock_server(self):
        """Clear both responses and recorded requests."""
        self._server.reset_responses()
        self._server.clear_requests()

    def get_request_count(self, method: str, path_pattern: str) -> int:
        """Return the number of requests matching method + path regex."""
        return self._server.get_request_count(method, path_pattern)

    def get_last_request_body(self, method: str, path_pattern: str) -> str:
        """Return the body of the last matching request, or empty string."""
        result = self._server.get_last_request_body(method, path_pattern)
        return result if result is not None else ""

    def request_was_made(self, method: str, path_pattern: str):
        """Fail if no request matching method + path was recorded."""
        count = self._server.get_request_count(method, path_pattern)
        if count == 0:
            raise AssertionError(
                f"Expected at least one {method} request matching '{path_pattern}' "
                f"but none was recorded.\n"
                f"Recorded requests: {self._server.get_all_requests()}"
            )

    def request_was_not_made(self, method: str, path_pattern: str):
        """Fail if any request matching method + path was recorded."""
        count = self._server.get_request_count(method, path_pattern)
        if count > 0:
            raise AssertionError(
                f"Expected no {method} request matching '{path_pattern}' "
                f"but {count} was recorded."
            )
