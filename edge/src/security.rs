#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dashboard_csp_blocks_scripts_and_network() {
        let csp = dashboard_csp();
        assert!(csp.contains("script-src 'none'"));
        assert!(csp.contains("connect-src 'none'"));
        assert!(csp.contains("frame-ancestors 'none'"));
        assert!(csp.contains("form-action 'none'"));
        assert!(csp.contains("base-uri 'none'"));
    }

    #[test]
    fn sha256_is_lowercase_hex() {
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn etag_is_stable_for_same_bundle() {
        let a = bundle_etag(b"<html>x</html>", b"body{}", 7);
        let b = bundle_etag(b"<html>x</html>", b"body{}", 7);
        assert_eq!(a, b);
        assert!(a.starts_with('"') && a.ends_with('"'));
    }

    #[test]
    fn required_dashboard_headers_are_present() {
        let headers = dashboard_security_headers();
        assert_eq!(headers.get("X-Content-Type-Options"), Some(&"nosniff"));
        assert_eq!(headers.get("Referrer-Policy"), Some(&"no-referrer"));
        assert_eq!(
            headers.get("Permissions-Policy"),
            Some(&"camera=(), microphone=(), geolocation=()")
        );
        assert_eq!(
            headers.get("Cross-Origin-Resource-Policy"),
            Some(&"same-origin")
        );
    }
}
