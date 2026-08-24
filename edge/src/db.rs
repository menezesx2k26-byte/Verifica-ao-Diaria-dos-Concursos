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

#[cfg(target_arch = "wasm32")]
mod runtime {
    use super::*;
    use crate::dashboard::{DashboardConfig, DashboardSection};
    use crate::error::AppError;
    use crate::models::{AlertDto, ContestDto, SourceHealthDto};
    use serde::Deserialize;
    use worker::d1::D1Database;

    const CONTEST_FIELDS: &str = "id, organization, title, city, uf, region, scope, type, education, area, remuneration, vacancies, fee, registration_start, registration_end, status, source, source_url, edital_url, priority, active, first_seen, last_seen, updated_at";

    #[derive(Debug, Deserialize)]
    struct DashboardConfigRow {
        version: i64,
        schema_version: i64,
        style_version: i64,
        min_app_version: String,
        published_at: String,
        sections_json: String,
    }

    pub async fn list_contests(
        db: &D1Database,
        filters: &ContestFilters,
    ) -> Result<Vec<ContestDto>, AppError> {
        let (sql, params) = build_contest_query(filters);
        let statement = db
            .prepare(&sql)
            .bind_refs(params.iter())
            .map_err(|_| AppError::Internal)?;
        let result = statement.all().await.map_err(|_| AppError::Internal)?;
        result.results().map_err(|_| AppError::Internal)
    }

    pub async fn get_contest(
        db: &D1Database,
        id: &str,
    ) -> Result<Option<ContestDto>, AppError> {
        let sql = format!(
            "SELECT {CONTEST_FIELDS} FROM contests WHERE active = 1 AND relevance_status = ? AND id = ? LIMIT 1"
        );
        let params = ["ACCEPTED".to_string(), id.to_string()];
        db.prepare(&sql)
            .bind_refs(params.iter())
            .map_err(|_| AppError::Internal)?
            .first(None)
            .await
            .map_err(|_| AppError::Internal)
    }

    pub async fn list_alerts(db: &D1Database) -> Result<Vec<AlertDto>, AppError> {
        let result = db
            .prepare(
                "SELECT id, event_id, title, body, url, priority, created_at FROM alerts ORDER BY priority DESC, created_at DESC LIMIT 100",
            )
            .all()
            .await
            .map_err(|_| AppError::Internal)?;
        result.results().map_err(|_| AppError::Internal)
    }

    pub async fn list_sources(db: &D1Database) -> Result<Vec<SourceHealthDto>, AppError> {
        let result = db
            .prepare(
                "SELECT id, label, url, http_ok, parser_ok, semantic_ok, item_count, expected_min, checked_at, last_success_at, scan_status, error FROM source_health ORDER BY label ASC",
            )
            .all()
            .await
            .map_err(|_| AppError::Internal)?;
        result.results().map_err(|_| AppError::Internal)
    }

    pub async fn load_published_dashboard(
        db: &D1Database,
    ) -> Result<Option<(DashboardConfig, String)>, AppError> {
        let row: Option<DashboardConfigRow> = db
            .prepare(
                "SELECT version, schema_version, style_version, min_app_version, published_at, sections_json FROM dashboard_configs WHERE status = 'published' ORDER BY version DESC LIMIT 1",
            )
            .first(None)
            .await
            .map_err(|_| AppError::Internal)?;

        let Some(row) = row else {
            return Ok(None);
        };
        let sections: Vec<DashboardSection> =
            serde_json::from_str(&row.sections_json).map_err(|_| AppError::Internal)?;
        let schema_version = u32::try_from(row.schema_version).map_err(|_| AppError::Internal)?;
        let dashboard_version = u64::try_from(row.version).map_err(|_| AppError::Internal)?;
        let style_version = u64::try_from(row.style_version).map_err(|_| AppError::Internal)?;
        Ok(Some((
            DashboardConfig {
                schema_version,
                dashboard_version,
                style_version,
                min_app_version: row.min_app_version,
                sections,
            },
            row.published_at,
        )))
    }
}

#[cfg(target_arch = "wasm32")]
pub use runtime::{
    get_contest, list_alerts, list_contests, list_sources, load_published_dashboard,
};

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
