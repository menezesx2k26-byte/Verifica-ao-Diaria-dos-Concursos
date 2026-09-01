use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ApiEnvelope<T> {
    pub schema_version: u32,
    pub items: T,
}

impl<T> ApiEnvelope<T> {
    pub const fn new(items: T) -> Self {
        Self {
            schema_version: 1,
            items,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
pub struct ContestDto {
    pub id: String,
    pub organization: String,
    pub title: String,
    pub city: String,
    pub uf: String,
    pub region: String,
    pub scope: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub education: String,
    pub area: String,
    pub remuneration: String,
    pub vacancies: String,
    pub fee: String,
    #[serde(rename = "start_date")]
    pub registration_start: String,
    #[serde(rename = "end_date")]
    pub registration_end: String,
    pub status: String,
    pub source: String,
    #[serde(rename = "url")]
    pub source_url: String,
    pub edital_url: String,
    pub priority: i32,
    pub active: bool,
    pub first_seen: String,
    pub last_seen: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
pub struct AlertDto {
    pub id: i64,
    pub event_id: Option<String>,
    pub title: String,
    pub body: String,
    pub url: String,
    pub priority: i32,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
pub struct SourceHealthDto {
    pub id: String,
    pub label: String,
    pub url: String,
    pub http_ok: bool,
    pub parser_ok: bool,
    pub semantic_ok: bool,
    pub item_count: i32,
    pub expected_min: i32,
    pub checked_at: String,
    pub last_success_at: String,
    pub scan_status: String,
    pub error: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
pub struct ContestFeedDto {
    pub schema_version: u32,
    pub updated_at: String,
    pub source_count: usize,
    pub items: Vec<ContestDto>,
    pub source_health: Vec<SourceHealthDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct HealthDto {
    pub ok: bool,
    pub service: &'static str,
    pub version: &'static str,
}

impl Default for HealthDto {
    fn default() -> Self {
        Self {
            ok: true,
            service: "concursos-watch-edge",
            version: env!("CARGO_PKG_VERSION"),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ErrorDto {
    pub error: &'static str,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn api_envelope_has_versioned_contract() {
        let envelope = ApiEnvelope::new(vec!["ok"]);
        assert_eq!(envelope.schema_version, 1);
        assert_eq!(envelope.items, vec!["ok"]);
    }

    #[test]
    fn contest_dto_keeps_public_fields_only() {
        let contest = ContestDto::default();
        let json = serde_json::to_value(contest).unwrap();
        assert!(json.get("title").is_some());
        assert!(json.get("start_date").is_some());
        assert!(json.get("end_date").is_some());
        assert!(json.get("url").is_some());
        assert!(json.get("relevance_status").is_none());
        assert!(json.get("sync_generation").is_none());
    }
}
