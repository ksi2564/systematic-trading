package my.side.trading.core.adapter.in.web.common;

public record ApiResponse<T>(String status, String message, T data) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("success", "Request processed successfully", data);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>("error", message, null);
    }

    public static <T> ApiResponse<T> error(String message, T data) {
        return new ApiResponse<>("error", message, data);
    }
}
