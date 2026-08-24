import unittest

from validate_dashboard_bundle import validate_css, validate_html


class DashboardBundleTests(unittest.TestCase):
    def test_rejects_script_and_event_handlers(self):
        with self.assertRaises(ValueError):
            validate_html("<main><script>alert(1)</script></main>")
        with self.assertRaises(ValueError):
            validate_html('<main><a href="#" onclick="x()">x</a></main>')

    def test_rejects_active_and_form_content(self):
        for tag in ("iframe", "object", "embed", "form", "input", "textarea", "select", "button"):
            with self.subTest(tag=tag):
                with self.assertRaises(ValueError):
                    validate_html(f"<main><{tag}></{tag}></main>")

    def test_rejects_remote_resources_and_meta_refresh(self):
        with self.assertRaises(ValueError):
            validate_html('<img src="https://evil.example/x.png">')
        with self.assertRaises(ValueError):
            validate_html('<meta http-equiv="refresh" content="0;url=https://evil.example">')

    def test_allows_known_local_css_and_internal_routes(self):
        validate_html(
            '<main><link rel="stylesheet" href="/assets/dashboard.css">'
            '<a href="concursoswatch://alerts">Alertas</a></main>'
        )

    def test_rejects_remote_or_executable_css(self):
        bad = (
            '@import "x.css";',
            '.x{background:url(https://evil.example/x.png)}',
            '.x{background:url(//evil.example/x.png)}',
            '.x{background:javascript:alert(1)}',
            '.x{width:expression(alert(1))}',
        )
        for css in bad:
            with self.subTest(css=css):
                with self.assertRaises(ValueError):
                    validate_css(css)

    def test_allows_static_css_without_remote_urls(self):
        validate_css("body{margin:0;background:#090b10}.x{background:linear-gradient(#000,#111)}")


if __name__ == "__main__":
    unittest.main()
