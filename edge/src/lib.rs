pub mod db;
pub mod query;

pub fn route_key(method: &str, path: &str) -> &'static str {
    match (method, path) {
        ("GET", "/health") => "health",
        ("POST" | "PUT" | "PATCH" | "DELETE", "/health") => "method_not_allowed",
        (_, "/api/v1/ingest") => "not_found",
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
        assert_eq!(
            route_key("DELETE", "/api/v1/alerts"),
            "method_not_allowed"
        );
    }
}
