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
        assert!(json.get("relevance_status").is_none());
        assert!(json.get("sync_generation").is_none());
    }
}
