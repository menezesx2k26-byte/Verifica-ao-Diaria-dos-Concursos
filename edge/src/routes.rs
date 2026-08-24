use crate::dashboard::{render_dashboard, DashboardConfig, DashboardContest, DashboardData};
use crate::db::{get_contest, list_alerts, list_contests, list_sources, load_published_dashboard};
use crate::error::AppError;
use crate::models::{ApiEnvelope, ContestFeedDto, ErrorDto, HealthDto};
use crate::query::ContestFilters;
use crate::route_key;
use crate::security::{
    build_dashboard_manifest, bundle_etag, dashboard_asset_headers, dashboard_security_headers,
    DashboardManifestInput,
};
use serde::Serialize;
use worker::{Env, Request, Response};

const DASHBOARD_CSS: &str = include_str!("../assets/dashboard.css");
const DEFAULT_DASHBOARD_CONFIG: &str = include_str!("../../config/dashboard.json");
const BUNDLED_PUBLISHED_AT: &str = "2026-08-24T00:00:00Z";

struct DashboardBundle {
    config: DashboardConfig,
    published_at: String,
    html: String,
    css: &'static str,
    etag: String,
}

pub async fn handle(req: Request, env: Env) -> worker::Result<Response> {
    match dispatch(&req, &env).await {
        Ok(response) => Ok(response),
        Err(error) => public_error(error),
    }
}

async fn dispatch(req: &Request, env: &Env) -> Result<Response, AppError> {
    let method = req.method().to_string();
    let path = req.path();
    match route_key(&method, &path) {
        "health" => json_response(&HealthDto::default(), 200, "public, max-age=30"),
        "contests" => {
            let url = req.url().map_err(|_| AppError::BadRequest("invalid_url"))?;
            let filters = ContestFilters::from_url(&url).map_err(AppError::from)?;
            let db = env.d1("DB").map_err(|_| AppError::Internal)?;
            let items = list_contests(&db, &filters).await?;
            let source_health = list_sources(&db).await?;
            let updated_at = items
                .iter()
                .map(|item| item.updated_at.as_str())
                .chain(
                    source_health
                        .iter()
                        .map(|source| source.checked_at.as_str()),
                )
                .max()
                .unwrap_or_default()
                .to_string();
            let payload = ContestFeedDto {
                schema_version: 1,
                updated_at,
                source_count: source_health.len(),
                items,
                source_health,
            };
            json_response(
                &payload,
                200,
                "public, max-age=60, stale-while-revalidate=120",
            )
        }
        "contest_detail" => {
            let id = path
                .strip_prefix("/api/v1/contests/")
                .filter(|id| !id.is_empty())
                .ok_or(AppError::NotFound)?;
            let db = env.d1("DB").map_err(|_| AppError::Internal)?;
            let item = get_contest(&db, id).await?.ok_or(AppError::NotFound)?;
            json_response(&item, 200, "public, max-age=60, stale-while-revalidate=120")
        }
        "alerts" => {
            let db = env.d1("DB").map_err(|_| AppError::Internal)?;
            let payload = ApiEnvelope::new(list_alerts(&db).await?);
            json_response(
                &payload,
                200,
                "public, max-age=30, stale-while-revalidate=60",
            )
        }
        "sources" => {
            let db = env.d1("DB").map_err(|_| AppError::Internal)?;
            let payload = ApiEnvelope::new(list_sources(&db).await?);
            json_response(
                &payload,
                200,
                "public, max-age=60, stale-while-revalidate=120",
            )
        }
        "dashboard" => {
            let db = env.d1("DB").map_err(|_| AppError::Internal)?;
            let bundle = build_dashboard_bundle(&db).await?;
            if etag_matches(req, &bundle.etag)? {
                return not_modified(&bundle.etag, true);
            }
            let mut response = Response::from_html(&bundle.html).map_err(|_| AppError::Internal)?;
            apply_dashboard_headers(
                &mut response,
                bundle.html.as_bytes(),
                &bundle.etag,
                "text/html; charset=utf-8",
            )?;
            Ok(response)
        }
        "dashboard_css" => {
            let db = env.d1("DB").map_err(|_| AppError::Internal)?;
            let bundle = build_dashboard_bundle(&db).await?;
            if etag_matches(req, &bundle.etag)? {
                return not_modified(&bundle.etag, true);
            }
            let mut response = Response::ok(bundle.css).map_err(|_| AppError::Internal)?;
            apply_dashboard_headers(
                &mut response,
                bundle.css.as_bytes(),
                &bundle.etag,
                "text/css; charset=utf-8",
            )?;
            Ok(response)
        }
        "dashboard_manifest" => {
            let db = env.d1("DB").map_err(|_| AppError::Internal)?;
            let bundle = build_dashboard_bundle(&db).await?;
            if etag_matches(req, &bundle.etag)? {
                return not_modified(&bundle.etag, false);
            }
            let url = req.url().map_err(|_| AppError::BadRequest("invalid_url"))?;
            let origin = url.origin().ascii_serialization();
            let html_url = format!("{origin}/dashboard");
            let css_url = format!(
                "{origin}/assets/dashboard.css?v={}",
                bundle.config.style_version
            );
            let manifest = build_dashboard_manifest(DashboardManifestInput {
                dashboard_version: bundle.config.dashboard_version,
                style_version: bundle.config.style_version,
                min_app_version: &bundle.config.min_app_version,
                published_at: &bundle.published_at,
                html_url: &html_url,
                css_url: &css_url,
                html: bundle.html.as_bytes(),
                css: bundle.css.as_bytes(),
            });
            let mut response = Response::from_json(&manifest).map_err(|_| AppError::Internal)?;
            apply_common_headers(
                &mut response,
                "public, max-age=60, stale-while-revalidate=300",
            )?;
            response
                .headers_mut()
                .set("ETag", &bundle.etag)
                .map_err(|_| AppError::Internal)?;
            Ok(response)
        }
        "method_not_allowed" => Err(AppError::MethodNotAllowed),
        _ => Err(AppError::NotFound),
    }
}

async fn build_dashboard_bundle(db: &worker::d1::D1Database) -> Result<DashboardBundle, AppError> {
    let (config, published_at) = match load_published_dashboard(db).await? {
        Some(value) => value,
        None => (
            serde_json::from_str(DEFAULT_DASHBOARD_CONFIG).map_err(|_| AppError::Internal)?,
            BUNDLED_PUBLISHED_AT.to_string(),
        ),
    };

    let filters = ContestFilters {
        limit: 20,
        ..ContestFilters::default()
    };
    let contests = list_contests(db, &filters).await?;
    let urgent = contests
        .iter()
        .filter(|contest| contest.status == "closing_soon")
        .count();
    let headline = if urgent == 0 {
        "Tudo sob controle".to_string()
    } else if urgent == 1 {
        "1 prazo merece sua atenção".to_string()
    } else {
        format!("{urgent} prazos merecem sua atenção")
    };
    let data = DashboardData {
        headline,
        contests: contests
            .into_iter()
            .map(|contest| DashboardContest {
                id: contest.id,
                title: contest.title,
                organization: contest.organization,
                status: contest.status,
                registration_end: contest.registration_end,
                priority: contest.priority,
            })
            .collect(),
    };
    let html = render_dashboard(&config, &data).map_err(|_| AppError::Internal)?;
    let etag = bundle_etag(
        html.as_bytes(),
        DASHBOARD_CSS.as_bytes(),
        config.dashboard_version,
    );
    Ok(DashboardBundle {
        config,
        published_at,
        html,
        css: DASHBOARD_CSS,
        etag,
    })
}

fn json_response<T: Serialize>(
    payload: &T,
    status: u16,
    cache_control: &str,
) -> Result<Response, AppError> {
    let mut response = Response::from_json(payload)
        .map_err(|_| AppError::Internal)?
        .with_status(status);
    apply_common_headers(&mut response, cache_control)?;
    Ok(response)
}

fn apply_common_headers(response: &mut Response, cache_control: &str) -> Result<(), AppError> {
    response
        .headers_mut()
        .set("Cache-Control", cache_control)
        .map_err(|_| AppError::Internal)?;
    response
        .headers_mut()
        .set("X-Content-Type-Options", "nosniff")
        .map_err(|_| AppError::Internal)?;
    response
        .headers_mut()
        .set("Referrer-Policy", "no-referrer")
        .map_err(|_| AppError::Internal)?;
    Ok(())
}

fn apply_dashboard_headers(
    response: &mut Response,
    body: &[u8],
    etag: &str,
    content_type: &str,
) -> Result<(), AppError> {
    for (name, value) in dashboard_asset_headers(body, content_type, etag) {
        response
            .headers_mut()
            .set(name, &value)
            .map_err(|_| AppError::Internal)?;
    }
    Ok(())
}

fn etag_matches(req: &Request, etag: &str) -> Result<bool, AppError> {
    Ok(req
        .headers()
        .get("If-None-Match")
        .map_err(|_| AppError::Internal)?
        .is_some_and(|value| value.trim() == etag))
}

fn not_modified(etag: &str, dashboard_headers: bool) -> Result<Response, AppError> {
    let mut response = Response::empty()
        .map_err(|_| AppError::Internal)?
        .with_status(304);
    if dashboard_headers {
        for (name, value) in dashboard_security_headers() {
            response
                .headers_mut()
                .set(name, value)
                .map_err(|_| AppError::Internal)?;
        }
    }
    response
        .headers_mut()
        .set("ETag", etag)
        .map_err(|_| AppError::Internal)?;
    response
        .headers_mut()
        .set(
            "Cache-Control",
            "public, max-age=300, stale-while-revalidate=3600",
        )
        .map_err(|_| AppError::Internal)?;
    Ok(response)
}

fn public_error(error: AppError) -> worker::Result<Response> {
    let payload = ErrorDto {
        error: error.public_code(),
    };
    let mut response = Response::from_json(&payload)?.with_status(error.status_code());
    response.headers_mut().set("Cache-Control", "no-store")?;
    response
        .headers_mut()
        .set("X-Content-Type-Options", "nosniff")?;
    if error == AppError::MethodNotAllowed {
        response.headers_mut().set("Allow", "GET")?;
    }
    Ok(response)
}
