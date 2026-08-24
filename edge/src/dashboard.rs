use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DashboardError(pub &'static str);

impl Display for DashboardError {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.0)
    }
}

impl std::error::Error for DashboardError {}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct DashboardConfig {
    pub schema_version: u32,
    pub dashboard_version: u64,
    pub style_version: u64,
    pub min_app_version: String,
    pub sections: Vec<DashboardSection>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum DashboardSection {
    Attention { limit: usize },
    PriorityWatch,
    OpenContests { limit: usize },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
pub struct DashboardContest {
    pub id: String,
    pub title: String,
    pub organization: String,
    pub status: String,
    pub registration_end: String,
    pub priority: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
pub struct DashboardData {
    pub headline: String,
    pub contests: Vec<DashboardContest>,
}

pub fn escape_text(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for ch in input.chars() {
        match ch {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            _ => out.push(ch),
        }
    }
    out
}

fn render_contest_card(contest: &DashboardContest) -> String {
    let deadline = if contest.registration_end.is_empty() {
        "Prazo não informado".to_string()
    } else {
        format!("Inscrições até {}", escape_text(&contest.registration_end))
    };
    format!(
        "<article class=\"contest-card\"><p class=\"eyebrow\">{}</p><h3>{}</h3><p class=\"muted\">{}</p><p class=\"deadline\">{}</p></article>",
        escape_text(&contest.organization),
        escape_text(&contest.title),
        escape_text(&contest.status),
        deadline,
    )
}

fn render_attention(data: &DashboardData, limit: usize) -> String {
    let cards = data
        .contests
        .iter()
        .filter(|contest| matches!(contest.status.as_str(), "closing_soon" | "open"))
        .take(limit.min(10))
        .map(render_contest_card)
        .collect::<String>();

    let body = if cards.is_empty() {
        "<p class=\"empty\">Nenhuma ação urgente agora.</p>".to_string()
    } else {
        cards
    };
    format!(
        "<section class=\"section section-attention\"><div class=\"section-heading\"><p class=\"kicker\">Atenção</p><h2>O que precisa de você</h2></div><div class=\"card-stack\">{body}</div></section>"
    )
}

fn render_priority_watch(data: &DashboardData) -> String {
    let cards = data
        .contests
        .iter()
        .filter(|contest| contest.priority >= 100)
        .take(5)
        .map(render_contest_card)
        .collect::<String>();
    let body = if cards.is_empty() {
        "<p class=\"empty\">Nenhuma mudança relevante nos acompanhamentos prioritários.</p>"
            .to_string()
    } else {
        cards
    };
    format!(
        "<section class=\"section\"><div class=\"section-heading\"><p class=\"kicker\">Acompanhamentos</p><h2>Prioridade</h2></div><div class=\"card-stack\">{body}</div></section>"
    )
}

fn render_open_contests(data: &DashboardData, limit: usize) -> String {
    let cards = data
        .contests
        .iter()
        .filter(|contest| matches!(contest.status.as_str(), "open" | "closing_soon"))
        .take(limit.min(20))
        .map(render_contest_card)
        .collect::<String>();
    let body = if cards.is_empty() {
        "<p class=\"empty\">Nenhum concurso aberto dentro dos filtros atuais.</p>".to_string()
    } else {
        cards
    };
    format!(
        "<section class=\"section\"><div class=\"section-heading\"><p class=\"kicker\">Oportunidades</p><h2>Inscrições abertas</h2></div><div class=\"card-stack\">{body}</div></section>"
    )
}

pub fn render_dashboard(
    config: &DashboardConfig,
    data: &DashboardData,
) -> Result<String, DashboardError> {
    if config.schema_version != 1 {
        return Err(DashboardError("unsupported_dashboard_schema"));
    }

    let mut sections = String::new();
    for section in &config.sections {
        sections.push_str(&match section {
            DashboardSection::Attention { limit } => render_attention(data, *limit),
            DashboardSection::PriorityWatch => render_priority_watch(data),
            DashboardSection::OpenContests { limit } => render_open_contests(data, *limit),
        });
    }

    Ok(format!(
        "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"color-scheme\" content=\"light dark\"><title>Concursos Watch</title><link rel=\"stylesheet\" href=\"/assets/dashboard.css?v={}\"></head><body><main class=\"dashboard\"><header class=\"hero\"><p class=\"kicker\">Concursos Watch</p><h1>{}</h1><p class=\"hero-copy\">Só oportunidades validadas chegam aqui.</p></header>{}</main></body></html>",
        config.style_version,
        escape_text(&data.headline),
        sections,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn external_text_is_escaped() {
        assert_eq!(
            escape_text("<script>alert(1)</script>"),
            "&lt;script&gt;alert(1)&lt;/script&gt;"
        );
    }

    #[test]
    fn status_labels_are_human_readable() {
        assert_eq!(status_label("open"), "Inscrições abertas");
        assert_eq!(status_label("closing_soon"), "Encerra em breve");
        assert_eq!(status_label("announced"), "Anunciado");
        assert_eq!(status_label("detected"), "Detectado");
    }

    #[test]
    fn headline_reflects_closing_soon_items() {
        let contests = vec![DashboardContest {
            status: "closing_soon".into(),
            ..DashboardContest::default()
        }];
        assert_eq!(headline_for_contests(&contests), "1 prazo merece sua atenção");
    }

    #[test]
    fn rendered_dashboard_has_no_script_tag() {
        let config = DashboardConfig {
            schema_version: 1,
            dashboard_version: 1,
            style_version: 1,
            min_app_version: "4.0.0".into(),
            sections: vec![DashboardSection::Attention { limit: 3 }],
        };
        let data = DashboardData {
            headline: "Tudo sob controle <script>bad()</script>".into(),
            contests: vec![],
        };
        let html = render_dashboard(&config, &data).unwrap();
        assert!(!html.to_ascii_lowercase().contains("<script"));
        assert!(html.contains("&lt;script&gt;bad()&lt;/script&gt;"));
    }

    #[test]
    fn unsupported_schema_is_rejected() {
        let config = DashboardConfig {
            schema_version: 99,
            dashboard_version: 1,
            style_version: 1,
            min_app_version: "4.0.0".into(),
            sections: vec![],
        };
        assert!(render_dashboard(&config, &DashboardData::default()).is_err());
    }
}
