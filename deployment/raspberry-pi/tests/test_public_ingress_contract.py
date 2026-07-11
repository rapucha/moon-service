from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[3]
TEMPLATE = (
    ROOT
    / "deployment"
    / "raspberry-pi"
    / "roles"
    / "moon_service_host"
    / "templates"
    / "moon-service-public-nginx.conf.j2"
)
STATIC_ROOT = ROOT / "backend" / "src" / "main" / "resources" / "static"


def location_block(configuration: str, header: str) -> str:
    start = configuration.index(header)
    depth = 0
    entered = False
    for offset, character in enumerate(configuration[start:]):
        if character == "{":
            entered = True
            depth += 1
        elif character == "}":
            depth -= 1
            if entered and depth == 0:
                return configuration[start : start + offset + 1]
    raise AssertionError(f"Unterminated nginx block: {header}")


class PublicIngressContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.configuration = TEMPLATE.read_text(encoding="utf-8")

    def test_listener_and_proxy_protocol_trust_are_loopback_only(self):
        self.assertIn(
            "listen 127.0.0.1:{{ moon_service_public_ingress_port }} "
            "proxy_protocol default_server;",
            self.configuration,
        )
        self.assertNotRegex(self.configuration, r"listen\s+(?:0\.0\.0\.0|\[::\]|\*)")
        self.assertEqual(
            ["127.0.0.1"],
            re.findall(r"^\s*set_real_ip_from\s+([^;]+);", self.configuration, re.MULTILINE),
        )
        self.assertIn("real_ip_header proxy_protocol;", self.configuration)
        self.assertIn("real_ip_recursive off;", self.configuration)

    def test_upstream_is_the_exact_configured_application_listener(self):
        upstream = location_block(self.configuration, "upstream moon_service_public_app {")
        self.assertIn(
            "server {{ moon_service_bind_address }}:{{ moon_service_host_port }};",
            upstream,
        )
        self.assertNotIn("0.0.0.0", upstream)

    def test_public_surface_is_an_exact_get_head_allowlist(self):
        static_assets = {
            f"/{path.name}"
            for path in STATIC_ROOT.iterdir()
            if path.is_file() and path.suffix != ".html" and path.name != "types.js"
        }
        expected_paths = {
            "/",
            "/search",
            "/about",
            "/readyz",
            "/api/opportunities",
            *static_assets,
        }
        exact_paths = set(
            re.findall(r"^\s*location = (\S+) \{", self.configuration, re.MULTILINE)
        )

        self.assertEqual(expected_paths, exact_paths)
        self.assertNotIn("/index.html", exact_paths)
        self.assertNotIn("/about.html", exact_paths)

        for path in expected_paths:
            block = location_block(self.configuration, f"location = {path} {{")
            self.assertIn("limit_except GET { deny all; }", block, path)
            self.assertIn("proxy_pass http://moon_service_public_app;", block, path)

        blocked = location_block(
            self.configuration,
            "location ~ ^/(?:admin(?:/|$)|api/opportunities/search(?:/|$)|healthz/?$) {",
        )
        self.assertIn("return 404;", blocked)
        fallback = location_block(self.configuration, "location / {")
        self.assertIn("return 404;", fallback)

    def test_api_has_per_client_and_hard_aggregate_limits(self):
        self.assertRegex(
            self.configuration,
            r"limit_req_zone \$binary_remote_addr "
            r"zone=moon_service_public_api_clients:10m "
            r"rate=\{\{ moon_service_public_client_api_rate_per_minute \}\}r/m;",
        )
        self.assertIn(
            "limit_req_zone $server_name "
            "zone=moon_service_public_api_requests:1m "
            "rate={{ moon_service_public_api_rate_per_minute }}r/m;",
            self.configuration,
        )
        self.assertIn(
            "limit_req_zone $server_name "
            "zone=moon_service_public_requests:1m "
            "rate={{ moon_service_public_request_rate_per_minute }}r/m;",
            self.configuration,
        )
        self.assertIn(
            "limit_conn zone=moon_service_public_connections "
            "{{ moon_service_public_connection_limit }};",
            self.configuration,
        )
        self.assertIn(
            "limit_conn zone=moon_service_public_client_connections "
            "{{ moon_service_public_client_connection_limit }};",
            self.configuration,
        )
        self.assertIn("limit_req_status 429;", self.configuration)
        self.assertIn("limit_conn_status 429;", self.configuration)

        api = location_block(self.configuration, "location = /api/opportunities {")
        self.assertIn("zone=moon_service_public_requests", api)
        self.assertIn("zone=moon_service_public_api_requests", api)
        self.assertIn("zone=moon_service_public_api_clients", api)
        self.assertIn(
            "error_page 429 = @moon_service_public_api_rate_limited;",
            api,
        )

        response = location_block(
            self.configuration,
            "location @moon_service_public_api_rate_limited {",
        )
        self.assertIn("default_type application/json;", response)
        self.assertIn("return 429", response)
        self.assertIn('"status":"rate_limited"', response)
        self.assertIn('"retryAfterSeconds":60', response)
        self.assertIn("add_header Retry-After $moon_service_public_retry_after always;", self.configuration)

    def test_request_bounds_and_security_headers_are_explicit(self):
        for directive in (
            "client_max_body_size 1k;",
            "client_body_timeout 5s;",
            "client_header_timeout 5s;",
            "client_header_buffer_size 1k;",
            "large_client_header_buffers 2 4k;",
            "keepalive_timeout 10s;",
            "keepalive_requests 50;",
            "send_timeout 15s;",
            "proxy_connect_timeout 3s;",
            "proxy_send_timeout 5s;",
            "proxy_read_timeout 45s;",
        ):
            self.assertIn(directive, self.configuration)

        for header in (
            "Content-Security-Policy",
            "Cross-Origin-Opener-Policy",
            "Cross-Origin-Resource-Policy",
            "Permissions-Policy",
            "Referrer-Policy",
            "Strict-Transport-Security",
            "X-Content-Type-Options",
            "X-Frame-Options",
        ):
            self.assertRegex(self.configuration, rf"add_header {header} .+ always;")

    def test_only_fixed_limiter_events_are_logged(self):
        self.assertIn("syslog:server=unix:/dev/log", self.configuration)
        self.assertIn("if=$moon_service_public_limiter_event", self.configuration)
        access_logs = re.findall(
            r"^\s*access_log\s+(.+?);",
            self.configuration,
            re.MULTILINE | re.DOTALL,
        )
        self.assertEqual(1, len(access_logs))
        self.assertIn("syslog:server=unix:/dev/log", access_logs[0])
        self.assertIn("if=$moon_service_public_limiter_event", access_logs[0])
        self.assertIn("limit_req_log_level info;", self.configuration)
        self.assertIn("limit_conn_log_level info;", self.configuration)

        log_format_start = self.configuration.index("log_format moon_service_public_limiter")
        log_format_end = self.configuration.index(";", log_format_start)
        log_format = self.configuration[log_format_start:log_format_end]
        self.assertIn("'rate-limited'", log_format)
        self.assertNotIn("$request_uri", log_format)
        self.assertNotIn("$uri", log_format)
        self.assertNotIn("$remote_addr", log_format)
        self.assertNotIn("$http_", log_format)

        self.assertNotIn("$request_uri", self.configuration)
        self.assertIn(
            "error_log syslog:server=unix:/dev/log,facility=local6,"
            "tag=moon-service-public-error crit;",
            self.configuration,
        )
        self.assertNotIn("/var/log/nginx/", self.configuration)


if __name__ == "__main__":
    unittest.main()
