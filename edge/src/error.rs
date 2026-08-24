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
