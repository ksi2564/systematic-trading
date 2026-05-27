package my.side.trading.adapter.in.web.dashboard.dto;

public record DashboardPageInfo(
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasPrevious,
        boolean hasNext
) {
    public static DashboardPageInfo of(int page, int size, long totalElements) {
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new DashboardPageInfo(
                page,
                size,
                totalElements,
                totalPages,
                page > 0,
                page + 1 < totalPages);
    }
}
