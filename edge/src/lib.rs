pub mod dashboard;
pub mod db;
pub mod query;
pub mod security;

const MUTATING_METHODS: &[&str] = &["POST", "PUT", "PATCH", "DELETE"];

pub fn route_key(method: &str, path: &str) -> &'static str {
    if path == "/api/v1/ingest" {
        return "not_found";
    }

    let known = match path {
        "/health" => Some("health"),
        "/api/v1/contests" => Some("contests"),
        "/api/v1/alerts" => Some("alerts"),
        "/api/v1/sources" => Some("sources"),
        "/api/v1/dashboard-manifest" => Some("dashboard_manifest"),
        "/dashboard" => Some("dashboard"),
        "/assets/dashboard.css" => Some("dashboard_css"),
        _ if path
            .strip_prefix("/api/v1/contests/")
            .is_some_and(|id| !id.is_empty()) =>
        {
            Some("contest_detail")
        }
        _ => None,
    };

    match (method, known) {
        ("GET", Some(route)) => route,
        (m, Some(_)) if MUTATING_METHODS.contains(&m) => "method_not_allowed",
        _ => "not_found",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn health_route_is_read_only() {
        assert_eq!(route_key("GET", "/health"), "health");
        assert_eq!(route_key("POST", "/health"), "method_not_allowed");
    }

    #[test]
    fn public_ingest_does_not_exist() {
        assert_eq!(route_key("POST", "/api/v1/ingest"), "not_found");
    }

    #[test]
    fn contest_routes_are_read_only() {
        assert_eq!(route_key("GET", "/api/v1/contests"), "contests");
        assert_eq!(
            route_key("GET", "/api/v1/contests/abc123"),
            "contest_detail"
        );
        assert_eq!(route_key("POST", "/api/v1/contests"), "method_not_allowed");
    }

    #[test]
    fn alerts_and_sources_are_read_only() {
        assert_eq!(route_key("GET", "/api/v1/alerts"), "alerts");
        assert_eq!(route_key("GET", "/api/v1/sources"), "sources");
        assert_eq!(route_key("DELETE", "/api/v1/alerts"), "method_not_allowed");
    }

    #[test]
    fn dashboard_bundle_routes_are_read_only() {
        assert_eq!(
            route_key("GET", "/api/v1/dashboard-manifest"),
            "dashboard_manifest"
        );
        assert_eq!(route_key("GET", "/dashboard"), "dashboard");
        assert_eq!(route_key("GET", "/assets/dashboard.css"), "dashboard_css");
        assert_eq!(route_key("POST", "/dashboard"), "method_not_allowed");
    }
}
