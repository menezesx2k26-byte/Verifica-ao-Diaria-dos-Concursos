pub fn route_key(_method: &str, _path: &str) -> &'static str {
    "not_implemented"
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
}
