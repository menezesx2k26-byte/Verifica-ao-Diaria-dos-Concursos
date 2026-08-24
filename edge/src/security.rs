use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

pub fn dashboard_csp() -> &'static str {
    "default-src 'none'; style-src 'self'; img-src 'self' data:; font-src 'self'; script-src 'none'; connect-src 'none'; object-src 'none'; frame-src 'none'; frame-ancestors 'none'; form-action 'none'; base-uri 'none';"
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(digest.len() * 2);
    for byte in digest {
        use std::fmt::Write as _;
        let _ = write!(&mut out, "{byte:02x}");
    }
    out
}

pub fn bundle_etag(html: &[u8], css: &[u8], dashboard_version: u64) -> String {
    let mut hasher = Sha256::new();
    hasher.update(dashboard_version.to_be_bytes());
    hasher.update((html.len() as u64).to_be_bytes());
    hasher.update(html);
    hasher.update((css.len() as u64).to_be_bytes());
    hasher.update(css);
    let digest = hasher.finalize();
    let mut value = String::with_capacity(digest.len() * 2 + 2);
    value.push('"');
    for byte in digest {
        use std::fmt::Write as _;
        let _ = write!(&mut value, "{byte:02x}");
    }
    value.push('"');
    value
}

pub fn dashboard_security_headers() -> BTreeMap<&'static str, &'static str> {
    BTreeMap::from([
        ("Content-Security-Policy", dashboard_csp()),
        ("X-Content-Type-Options", "nosniff"),
        ("Referrer-Policy", "no-referrer"),
        (
            "Permissions-Policy",
            "camera=(), microphone=(), geolocation=()",
        ),
        ("Cross-Origin-Resource-Policy", "same-origin"),
    ])
}

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
