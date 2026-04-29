package id.ac.ui.cs.advprog.bidmartgateway.security;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class RoutePermissionPolicy {

    public String requiredPermission(HttpMethod method, String path) {
        if (HttpMethod.POST.equals(method) && matchesExact(path, "/api/v1/auctions")) {
            return "auction:create";
        }
        if (HttpMethod.POST.equals(method) && matchesChildAction(path, "/api/v1/auctions", "bids")) {
            return "bid:place";
        }
        if (HttpMethod.POST.equals(method) && matchesChildAction(path, "/api/v1/auctions", "close")) {
            return "auction:close";
        }
        if (HttpMethod.POST.equals(method) && matchesExact(path, "/api/v1/catalogue/listings")) {
            return "listing:create";
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
}
