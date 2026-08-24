#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_supported_filters_and_clamps_limit() {
        let url = url::Url::parse("https://x/api/v1/contests?scope=federal&uf=SC&education=n%C3%ADvel%20m%C3%A9dio&limit=999").unwrap();
        let f = ContestFilters::from_url(&url).unwrap();
        assert_eq!(f.scope.as_deref(), Some("federal"));
        assert_eq!(f.uf.as_deref(), Some("SC"));
        assert_eq!(f.education.as_deref(), Some("nível médio"));
        assert_eq!(f.limit, 100);
    }

    #[test]
    fn rejects_unknown_status() {
        let url = url::Url::parse("https://x/api/v1/contests?status=banana").unwrap();
        assert!(ContestFilters::from_url(&url).is_err());
    }

    #[test]
    fn defaults_are_bounded() {
        let url = url::Url::parse("https://x/api/v1/contests").unwrap();
        let f = ContestFilters::from_url(&url).unwrap();
        assert_eq!(f.limit, 50);
        assert_eq!(f.offset, 0);
    }
}
