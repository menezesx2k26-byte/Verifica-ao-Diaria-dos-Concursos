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
}
