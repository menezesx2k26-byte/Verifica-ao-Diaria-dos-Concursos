use crate::query::ContestFilters;

pub fn build_contest_query(filters: &ContestFilters) -> (String, Vec<String>) {
    let mut sql = String::from(
        "SELECT id, organization, title, city, uf, region, scope, type, education, area, \
         remuneration, vacancies, fee, registration_start, registration_end, status, source, \
         source_url, edital_url, priority, active, first_seen, last_seen, updated_at \
         FROM contests WHERE active = 1 AND relevance_status = ?",
    );
    let mut params = vec!["ACCEPTED".to_string()];

    push_eq(&mut sql, &mut params, "scope", filters.scope.as_deref());
    push_eq(&mut sql, &mut params, "region", filters.region.as_deref());
    push_eq(&mut sql, &mut params, "uf", filters.uf.as_deref());
    push_eq(
        &mut sql,
        &mut params,
        "education",
        filters.education.as_deref(),
    );
    push_eq(&mut sql, &mut params, "area", filters.area.as_deref());
    push_eq(&mut sql, &mut params, "type", filters.kind.as_deref());
    push_eq(&mut sql, &mut params, "status", filters.status.as_deref());

    for keyword in &filters.include_keywords {
        sql.push_str(" AND (title LIKE ? OR area LIKE ? OR organization LIKE ?)");
        let value = format!("%{keyword}%");
        params.extend([value.clone(), value.clone(), value]);
    }
    for keyword in &filters.exclude_keywords {
        sql.push_str(" AND NOT (title LIKE ? OR area LIKE ? OR organization LIKE ?)");
        let value = format!("%{keyword}%");
        params.extend([value.clone(), value.clone(), value]);
    }

    sql.push_str(" ORDER BY priority DESC, updated_at DESC LIMIT ? OFFSET ?");
    params.push(filters.limit.to_string());
    params.push(filters.offset.to_string());
    (sql, params)
}

fn push_eq(sql: &mut String, params: &mut Vec<String>, column: &str, value: Option<&str>) {
    if let Some(value) = value {
        sql.push_str(" AND ");
        sql.push_str(column);
        sql.push_str(" = ?");
        params.push(value.to_string());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
