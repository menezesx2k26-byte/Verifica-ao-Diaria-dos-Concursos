#[cfg(test)]
mod tests {
    use super::*;
    use crate::query::ContestFilters;

    #[test]
    fn accepted_only_is_first_invariant() {
        let filters = ContestFilters::default();
        let (sql, params) = build_contest_query(&filters);
        assert!(sql.contains("relevance_status = ?"));
        assert_eq!(params[0], "ACCEPTED");
    }

    #[test]
    fn values_never_enter_sql_text() {
        let mut filters = ContestFilters::default();
        filters.area = Some("x' OR 1=1 --".into());
        let (sql, params) = build_contest_query(&filters);
        assert!(!sql.contains("OR 1=1"));
        assert!(params.iter().any(|p| p == "x' OR 1=1 --"));
    }
}
