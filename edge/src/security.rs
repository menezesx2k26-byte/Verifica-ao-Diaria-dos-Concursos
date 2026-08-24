use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DashboardManifest {
    pub schema_version: u32,
    pub dashboard_version: u64,
    pub style_version: u64,
    pub min_app_version: String,
    pub published_at: String,
    pub html_url: String,
    pub css_url: String,
    pub html_sha256: String,
    pub css_sha256: String,
    pub etag: String,
}

pub struct DashboardManifestInput<'a> {
    pub dashboard_version: u64,
    pub style_version: u64,
    pub min_app_version: &'a str,
    pub published_at: &'a str,
    pub html_url: &'a str,
    pub css_url: &'a str,
    pub html: &'a [u8],
    pub css: &'a [u8],
}

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

pub fn build_dashboard_manifest(input: DashboardManifestInput<'_>) -> DashboardManifest {
    DashboardManifest {
        schema_version: 1,
        dashboard_version: input.dashboard_version,
        style_version: input.style_version,
        min_app_version: input.min_app_version.to_owned(),
        published_at: input.published_at.to_owned(),
        html_url: input.html_url.to_owned(),
        css_url: input.css_url.to_owned(),
        html_sha256: sha256_hex(input.html),
        css_sha256: sha256_hex(input.css),
        etag: bundle_etag(input.html, input.css, input.dashboard_version),
    }
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

    #[test]
    fn manifest_hashes_exact_bundle_bytes() {
        let html = b"<html>ok</html>";
        let css = b"body{}";
        let manifest = build_dashboard_manifest(DashboardManifestInput {
            dashboard_version: 9,
            style_version: 4,
            min_app_version: "4.0.0",
            published_at: "2026-08-24T09:00:00Z",
            html_url: "/dashboard",
            css_url: "/assets/dashboard.css",
            html,
            css,
        });
        assert_eq!(manifest.schema_version, 1);
        assert_eq!(manifest.dashboard_version, 9);
        assert_eq!(manifest.style_version, 4);
        assert_eq!(manifest.html_sha256, sha256_hex(html));
        assert_eq!(manifest.css_sha256, sha256_hex(css));
        assert_eq!(manifest.etag, bundle_etag(html, css, 9));
    }
}
