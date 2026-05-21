package id.ac.ui.cs.advprog.bidmartgateway.security;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class RoutePermissionPolicy {

    public String requiredPermission(HttpMethod method, String path) {
        // Admin routes
        if (path.startsWith("/api/v1/auth/admin/")) {
            return "admin:users";
        }
        if (matchesExact(path, "/api/v1/auth/roles") || matchesChildAction(path, "/api/v1/auth/users", "roles")) {
            return "admin:roles";
        }
        if (path.startsWith("/api/v1/auth/diagnostics")) {
            return "admin:users";
        }

        if (HttpMethod.POST.equals(method) && matchesExact(path, "/api/v1/listings")) {
            return "auction:create";
        }
        if (HttpMethod.POST.equals(method) && matchesChildAction(path, "/api/v1/listings", "bids")) {
            return "bid:place";
        }
        if (HttpMethod.POST.equals(method) && matchesChildAction(path, "/api/v1/listings", "close")) {
            return "auction:close";
        }
        // Catalogue listing mutations
        if (HttpMethod.POST.equals(method) && matchesExact(path, "/api/v1/catalogue/listings")) {
            return "listing:create";
        }
        if (HttpMethod.POST.equals(method) && matchesChildAction(path, "/api/v1/catalogue/listings", "admin/close")) {
            return "admin:users";
        }
        if (isCatalogueMutationRoute(method, path)) {
            return "listing:manage";
        }
        if (HttpMethod.POST.equals(method) && matchesChildAction(path, "/api/v1/orders", "dispute/resolve")) {
            return "admin:users";
        }
        if (path.startsWith("/api/v1/wallet/") || matchesExact(path, "/api/v1/wallet")) {
            return HttpMethod.GET.equals(method) ? "wallet:view" : "wallet:mutate";
        }
        return null;
    }

    private boolean matchesExact(String actualPath, String expectedPath) {
        return actualPath.equals(expectedPath) || actualPath.equals(expectedPath + "/");
    }

    private boolean matchesChildAction(String path, String prefix, String action) {
        String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return normalized.startsWith(prefix + "/") && normalized.endsWith("/" + action);
    }

    /**
     * Checks if the request is a mutation (PUT/DELETE/POST action) on a specific listing.
     * Matches patterns like:
     *   PUT    /api/v1/catalogue/listings/{id}
     *   DELETE /api/v1/catalogue/listings/{id}
     *   POST   /api/v1/catalogue/listings/{id}/publish
     *   POST   /api/v1/catalogue/listings/{id}/cancel
     *   POST   /api/v1/catalogue/listings/{id}/won
     *   POST   /api/v1/catalogue/listings/{id}/unsold
     */
    private boolean isCatalogueMutationRoute(HttpMethod method, String path) {
        String prefix = "/api/v1/catalogue/listings/";
        if (!path.startsWith(prefix)) {
            return false;
        }
        // PUT or DELETE on /api/v1/catalogue/listings/{id}
        if (HttpMethod.PUT.equals(method) || HttpMethod.DELETE.equals(method)) {
            return true;
        }
        // POST on /api/v1/catalogue/listings/{id}/action (e.g., publish, cancel)
        if (HttpMethod.POST.equals(method)) {
            String suffix = path.substring(prefix.length());
            // Has at least an id segment plus an action segment
            return suffix.contains("/");
        }
        return false;
    }
}
