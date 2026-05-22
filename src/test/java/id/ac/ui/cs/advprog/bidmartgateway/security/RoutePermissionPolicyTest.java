package id.ac.ui.cs.advprog.bidmartgateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoutePermissionPolicyTest {

    private final RoutePermissionPolicy policy = new RoutePermissionPolicy();

    @Test
    void adminUsersRouteRequiresAdminUsersPermission() {
        assertEquals("admin:users", policy.requiredPermission(HttpMethod.GET, "/api/v1/auth/admin/users"));
    }

    @Test
    void roleManagementRequiresAdminRoles() {
        assertEquals("admin:roles", policy.requiredPermission(HttpMethod.POST, "/api/v1/auth/roles"));
        assertEquals("admin:roles", policy.requiredPermission(HttpMethod.PUT, "/api/v1/auth/users/u1/roles"));
    }

    @Test
    void auctionMutationsMapToAuctionPermissions() {
        assertEquals("auction:create", policy.requiredPermission(HttpMethod.POST, "/api/v1/listings"));
        assertEquals("bid:place", policy.requiredPermission(HttpMethod.POST, "/api/v1/listings/a1/bids"));
        assertEquals("auction:close", policy.requiredPermission(HttpMethod.POST, "/api/v1/listings/a1/close"));
    }

    @Test
    void catalogueMutationsMapToListingPermissions() {
        assertEquals("listing:create", policy.requiredPermission(HttpMethod.POST, "/api/v1/catalogue/listings"));
        assertEquals("listing:manage", policy.requiredPermission(HttpMethod.PUT, "/api/v1/catalogue/listings/l1"));
        assertEquals("listing:manage", policy.requiredPermission(HttpMethod.DELETE, "/api/v1/catalogue/listings/l1"));
        assertEquals("listing:manage", policy.requiredPermission(HttpMethod.POST, "/api/v1/catalogue/listings/l1/publish"));
        assertEquals("admin:users", policy.requiredPermission(HttpMethod.POST, "/api/v1/catalogue/listings/l1/admin/close"));
    }

    @Test
    void walletRoutesMapToWalletPermissions() {
        assertEquals("wallet:view", policy.requiredPermission(HttpMethod.GET, "/api/v1/wallet/u1"));
        assertEquals("wallet:mutate", policy.requiredPermission(HttpMethod.POST, "/api/v1/wallet/u1/top-up"));
    }

    @Test
    void readOnlyRoutesReturnNull() {
        assertNull(policy.requiredPermission(HttpMethod.GET, "/api/v1/catalogue/listings/search"));
    }
}
