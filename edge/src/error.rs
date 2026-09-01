#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AppError {
    BadRequest(&'static str),
    NotFound,
    MethodNotAllowed,
    Internal,
}

impl AppError {
    pub const fn status_code(&self) -> u16 {
        match self {
            Self::BadRequest(_) => 400,
            Self::NotFound => 404,
            Self::MethodNotAllowed => 405,
            Self::Internal => 500,
        }
    }

    pub const fn public_code(&self) -> &'static str {
        match self {
            Self::BadRequest(code) => code,
            Self::NotFound => "not_found",
            Self::MethodNotAllowed => "method_not_allowed",
            Self::Internal => "internal_error",
        }
    }
}

impl From<String> for AppError {
    fn from(value: String) -> Self {
        match value.as_str() {
            "invalid_scope" => Self::BadRequest("invalid_scope"),
            "invalid_uf" => Self::BadRequest("invalid_uf"),
            "invalid_status" => Self::BadRequest("invalid_status"),
            "invalid_limit" => Self::BadRequest("invalid_limit"),
            "invalid_offset" => Self::BadRequest("invalid_offset"),
            "unknown_filter" => Self::BadRequest("unknown_filter"),
            _ => Self::BadRequest("invalid_request"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn public_error_mapping_is_controlled() {
        assert_eq!(AppError::BadRequest("invalid_status").status_code(), 400);
        assert_eq!(AppError::NotFound.status_code(), 404);
        assert_eq!(AppError::MethodNotAllowed.status_code(), 405);
        assert_eq!(AppError::Internal.status_code(), 500);
        assert_eq!(AppError::Internal.public_code(), "internal_error");
    }
}
