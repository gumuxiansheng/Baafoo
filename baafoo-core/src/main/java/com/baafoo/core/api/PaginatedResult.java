package com.baafoo.core.api;

import java.util.List;

/**
 * Paginated result wrapper for list API responses.
 *
 * @param <T> item type
 */
public class PaginatedResult<T> {

    /** Current page number (1-based) */
    private int page;

    /** Page size */
    private int size;

    /** Total item count */
    private long total;

    /** Total page count */
    private int totalPages;

    /** Items on the current page */
    private List<T> items;

    public PaginatedResult() {
    }

    public PaginatedResult(int page, int size, long total, List<T> items) {
        this.page = page;
        this.size = size;
        this.total = total;
        this.totalPages = (int) Math.ceil((double) total / size);
        this.items = items;
    }

    // --- Getters / Setters ---

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public long getTotal() { return total; }
    public void setTotal(long total) {
        this.total = total;
        if (this.size > 0) {
            this.totalPages = (int) Math.ceil((double) total / this.size);
        }
    }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public List<T> getItems() { return items; }
    public void setItems(List<T> items) { this.items = items; }
}
