use concursos_watch_edge::dashboard::{
    headline_for_contests, render_dashboard, DashboardConfig, DashboardContest, DashboardData,
};
use std::error::Error;
use std::fs;
use std::path::Path;

fn main() -> Result<(), Box<dyn Error>> {
    let config: DashboardConfig =
        serde_json::from_str(&fs::read_to_string("config/dashboard.json")?)?;
    let contests = vec![
        DashboardContest {
            id: "pg-004-2024-acs".into(),
            title: "Agente Comunitário de Saúde — 004/2024".into(),
            organization: "Prefeitura de Praia Grande".into(),
            status: "open".into(),
            registration_end: "".into(),
            priority: 120,
        },
        DashboardContest {
            id: "sv-02-2026-atg".into(),
            title: "Assistente-Técnico de Gestão — 02/2026".into(),
            organization: "Prefeitura de São Vicente".into(),
            status: "closing_soon".into(),
            registration_end: "2026-08-31".into(),
            priority: 120,
        },
        DashboardContest {
            id: "federal-sc-demo".into(),
            title: "Processo seletivo para docência".into(),
            organization: "Instituição Federal em Santa Catarina".into(),
            status: "open".into(),
            registration_end: "2026-09-05".into(),
            priority: 80,
        },
    ];
    let data = DashboardData {
        headline: headline_for_contests(&contests),
        contests,
    };
    let html = render_dashboard(&config, &data)?;
    let css = fs::read_to_string("edge/assets/dashboard.css")?;

    let output = Path::new("edge/test-output");
    fs::create_dir_all(output.join("assets"))?;
    fs::write(output.join("dashboard.html"), html)?;
    fs::write(output.join("dashboard.css"), &css)?;
    fs::write(output.join("assets/dashboard.css"), css)?;
    println!("dashboard preview -> {}", output.display());
    Ok(())
}
