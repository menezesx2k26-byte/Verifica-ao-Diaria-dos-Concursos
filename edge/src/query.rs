use url::Url;

const STATUS: &[&str] = &["open", "closing_soon", "announced", "detected"];
const SCOPE: &[&str] = &["federal", "estadual", "municipal"];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContestFilters {
    pub scope: Option<String>,
    pub region: Option<String>,
    pub uf: Option<String>,
    pub education: Option<String>,
    pub area: Option<String>,
    pub kind: Option<String>,
    pub status: Option<String>,
    pub include_keywords: Vec<String>,
    pub exclude_keywords: Vec<String>,
    pub limit: u32,
    pub offset: u32,
}

impl Default for ContestFilters {
    fn default() -> Self {
        Self {
            scope: None,
            region: None,
            uf: None,
            education: None,
            area: None,
            kind: None,
            status: None,
            include_keywords: Vec::new(),
            exclude_keywords: Vec::new(),
            limit: 50,
            offset: 0,
        }
    }
}

impl ContestFilters {
    pub fn from_url(url: &Url) -> Result<Self, String> {
        let mut out = Self::default();
        for (key, value) in url.query_pairs() {
            let value = value.trim().to_string();
            match key.as_ref() {
                "scope" => {
                    if !SCOPE.contains(&value.as_str()) {
                        return Err("invalid_scope".into());
                    }
                    out.scope = Some(value);
                }
                "region" => out.region = non_empty(value),
                "uf" => {
                    let uf = value.to_ascii_uppercase();
                    if uf.len() != 2 || !uf.chars().all(|c| c.is_ascii_alphabetic()) {
                        return Err("invalid_uf".into());
                    }
                    out.uf = Some(uf);
                }
                "education" => out.education = non_empty(value),
                "area" => out.area = non_empty(value),
                "type" => out.kind = non_empty(value),
                "status" => {
                    if !STATUS.contains(&value.as_str()) {
                        return Err("invalid_status".into());
                    }
                    out.status = Some(value);
                }
                "include" => push_csv(&mut out.include_keywords, &value),
                "exclude" => push_csv(&mut out.exclude_keywords, &value),
                "limit" => {
                    let parsed = value.parse::<u32>().map_err(|_| "invalid_limit")?;
                    out.limit = parsed.clamp(1, 100);
                }
                "offset" => {
                    out.offset = value.parse::<u32>().map_err(|_| "invalid_offset")?;
                }
                "" => {}
                _ => return Err("unknown_filter".into()),
            }
        }
        Ok(out)
    }
}

fn non_empty(value: String) -> Option<String> {
    (!value.is_empty()).then_some(value)
}

fn push_csv(target: &mut Vec<String>, value: &str) {
    target.extend(
        value
            .split(',')
            .map(str::trim)
            .filter(|v| !v.is_empty())
            .take(20)
            .map(ToOwned::to_owned),
    );
    target.truncate(20);
}

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
