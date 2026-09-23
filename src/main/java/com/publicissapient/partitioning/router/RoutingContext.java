package com.publicissapient.partitioning.router;

/**
 * Request-scoped routing context, populated by {@code TenantRoutingInterceptor}
 * (or set explicitly by a service) from an inbound header such as
 * {@code X-Tenant-Region}. Consulted by {@link DynamicPartitionRouter} to
 * decide which physical collection a read/write should be directed to.
 *
 * Uses a ThreadLocal because each HTTP request in this synchronous (MVC)
 * flow is handled on its own thread; it is cleared at the end of every
 * request by the controller layer's try/finally to prevent leakage across
 * threads in the servlet container's thread pool.
 */
public final class RoutingContext {

    private static final ThreadLocal<String> CURRENT_REGION = new ThreadLocal<>();

    private RoutingContext() {
    }

    public static void setRegion(String region) {
        CURRENT_REGION.set(region);
    }

    public static String getRegion() {
        return CURRENT_REGION.get();
    }

    public static void clear() {
        CURRENT_REGION.remove();
    }
}
