#!/usr/bin/env node

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const DEFAULT_GATEWAY_URL = 'http://localhost:8000';
const DEFAULT_FRONTEND_URL = 'http://localhost:5173';
const DEFAULT_REQUEST_TIMEOUT_MS = 15_000;
const DEFAULT_POLL_TIMEOUT_MS = 180_000;
const DEFAULT_POLL_INTERVAL_MS = 1_000;
const DEFAULT_TOP_UP_CENTS = 250_000;
const DEFAULT_AUCTION_LIFETIME_SECONDS = 8;

class RegressionError extends Error {
    constructor(message, details = {}) {
        super(message);
        this.name = 'RegressionError';
        this.details = details;
    }
}

const startedAt = new Date();
const argv = process.argv.slice(2);
const env = process.env;

if (hasArg('--help') || hasArg('-h')) {
    printHelp();
    process.exit(0);
}

const config = {
    gatewayUrl: stripTrailingSlash(env.BIDMART_GATEWAY_URL || env.BIDMART_BASE_URL || DEFAULT_GATEWAY_URL),
    frontendUrl: stripTrailingSlash(env.BIDMART_FRONTEND_URL || DEFAULT_FRONTEND_URL),
    scope: normalizeScope(argValue('--scope') || env.BIDMART_REGRESSION_SCOPE || 'full'),
    environment: normalizeEnvironment(env.BIDMART_E2E_ENV || env.BIDMART_DEPLOY_ENV || ''),
    runId: sanitizeRunId(env.BIDMART_E2E_RUN_ID || new Date().toISOString()),
    adminEmail: envValue('BIDMART_E2E_ADMIN_EMAIL') || envValue('BIDMART_ADMIN_EMAIL'),
    adminPassword: envValue('BIDMART_E2E_ADMIN_PASSWORD') || envValue('BIDMART_ADMIN_PASSWORD'),
    internalToken:
        envValue('BIDMART_E2E_INTERNAL_TOKEN')
        || envValue('BIDMART_INTERNAL_SERVICE_TOKEN')
        || envValue('GATEWAY_INTERNAL_TOKEN')
        || 'bidmart-local-internal-token',
    allowProductionSynthetic: env.BIDMART_E2E_ALLOW_PROD_SYNTHETIC === '1',
    requestTimeoutMs: positiveInt(env.BIDMART_REQUEST_TIMEOUT_MS, DEFAULT_REQUEST_TIMEOUT_MS),
    pollTimeoutMs: positiveInt(env.BIDMART_POLL_TIMEOUT_MS, DEFAULT_POLL_TIMEOUT_MS),
    pollIntervalMs: positiveInt(env.BIDMART_POLL_INTERVAL_MS, DEFAULT_POLL_INTERVAL_MS),
    auctionLifetimeSeconds: positiveInt(env.BIDMART_AUCTION_LIFETIME_SECONDS, DEFAULT_AUCTION_LIFETIME_SECONDS),
    topUpCents: positiveInt(env.BIDMART_REGRESSION_TOP_UP_CENTS, DEFAULT_TOP_UP_CENTS),
    concurrencyFanout: positiveInt(env.BIDMART_REGRESSION_CONCURRENCY, 100),
    skipFrontend: env.BIDMART_SKIP_FRONTEND_CHECK === '1',
    logDir: env.BIDMART_REGRESSION_LOG_DIR || 'spec-regression-results',
};

config.environment ||= inferEnvironment(config.gatewayUrl);
config.syntheticPrefix = `bidmart-e2e-${config.environment}-${config.runId}`;

const results = [];
const state = {
    admin: null,
    users: new Map(),
    syntheticEmails: new Set(),
    listingIds: new Set(),
};

main().catch(async (error) => {
    console.error('');
    console.error('BidMart spec regression failed.');
    if (error instanceof RegressionError) {
        console.error(error.message);
        if (Object.keys(error.details).length > 0) {
            console.error(JSON.stringify(error.details, null, 2));
        }
    } else {
        console.error(error?.stack || String(error));
    }
    await cleanupSyntheticData().catch((cleanupError) => {
        console.error(`Synthetic cleanup failed: ${cleanupError?.message || cleanupError}`);
    });
    writeRunLog('failed', error);
    process.exit(1);
});

async function main() {
    console.log('BidMart full spec regression');
    console.log(`Gateway:     ${config.gatewayUrl}`);
    console.log(`Frontend:    ${config.frontendUrl}`);
    console.log(`Environment: ${config.environment}`);
    console.log(`Run id:      ${config.runId}`);
    console.log(`Scope:       ${config.scope}`);
    console.log(`Synthetic:   ${config.syntheticPrefix}`);
    console.log('');

    if (hasArg('--list')) {
        printTraceIds();
        return;
    }

    validateSafety();

    await runCheck('API-001', 'gateway health and public catalogue are reachable', async () => {
        await api('/actuator/health', { expectedStatuses: [200], label: 'gateway health' });
        await api('/api/v1/catalogue/listings/search?page=0&size=1', {
            expectedStatuses: [200],
            label: 'catalogue search',
        });
        if (!config.skipFrontend) {
            await request(config.frontendUrl, { expectedStatuses: [200], label: 'frontend shell' });
        }
    });

    if (config.scope === 'public') {
        writeRunLog('passed');
        console.log('');
        console.log('Public regression passed.');
        return;
    }

    state.admin = await runCheck('AUTH-ADMIN-001', 'admin can authenticate for synthetic orchestration', () =>
        login(config.adminEmail, config.adminPassword, 'ADMIN')
    );

    await runCheck('REG-001', 'registration validation, duplicate handling, and synthetic verification', async () => {
        await api('/api/v1/auth/register', {
            method: 'POST',
            body: { email: 'plaintext', password: 'BidmartE2E1!' },
            expectedStatuses: [400],
            label: 'invalid email registration',
        });
        await api('/api/v1/auth/register', {
            method: 'POST',
            body: { email: `${uniqueLocal('blank-password')}@e2e.bidmart.local`, password: '' },
            expectedStatuses: [400],
            label: 'blank password registration',
        });

        const duplicate = syntheticEmail('duplicate');
        await registerRaw(duplicate, syntheticPassword('duplicate'));
        await verifySyntheticUser(duplicate);
        await api('/api/v1/auth/register', {
            method: 'POST',
            body: { email: duplicate, password: syntheticPassword('duplicate') },
            expectedStatuses: [400, 409],
            label: 'duplicate registration',
        });
        await verifySyntheticUser(duplicate, [204]);
    });

    const seller = await runCheck('REG-SELLER-001', 'synthetic seller can register, verify, login, and onboard', () =>
        provisionSyntheticActor('seller', 'SELLER')
    );
    let buyer = await runCheck('REG-BUYER-001', 'synthetic buyer can register, verify, login, and onboard', () =>
        provisionSyntheticActor('buyer', 'BUYER')
    );
    const outbidBuyer = await runCheck('REG-BUYER-002', 'second synthetic buyer is available for race and outbid checks', () =>
        provisionSyntheticActor('buyer2', 'BUYER')
    );

    await runCheck('AUTH-001', 'login, refresh, revoked refresh token, sessions, and deactivation behaviour', async () => {
        await api('/api/v1/auth/login', {
            method: 'POST',
            body: { email: buyer.email, password: 'wrong-password' },
            expectedStatuses: [401],
            label: 'wrong password login',
        });
        await api('/api/v1/auth/login', {
            method: 'POST',
            body: { email: syntheticEmail('nonexistent'), password: syntheticPassword('nonexistent') },
            expectedStatuses: [401],
            label: 'nonexistent user login',
        });

        const loginResponse = await login(buyer.email, buyer.password, 'BUYER');
        await api('/api/v1/auth/sessions', {
            actor: loginResponse,
            expectedStatuses: [200],
            label: 'active sessions',
        });
        const refreshed = await api('/api/v1/auth/refresh', {
            method: 'POST',
            body: { refreshToken: loginResponse.refreshToken },
            expectedStatuses: [200],
            label: 'refresh token',
        });
        assert(refreshed.payload?.accessToken, 'refresh did not issue access token', refreshed.payload);
        await api('/api/v1/auth/logout', {
            method: 'POST',
            body: { refreshToken: loginResponse.refreshToken },
            expectedStatuses: [204],
            label: 'logout revokes refresh token',
        });
        await api('/api/v1/auth/refresh', {
            method: 'POST',
            body: { refreshToken: loginResponse.refreshToken },
            expectedStatuses: [401],
            label: 'refresh after revoke',
        });

        await api(`/api/v1/auth/admin/disable-user?email=${encodeURIComponent(buyer.email)}`, {
            method: 'POST',
            actor: state.admin,
            expectedStatuses: [204],
            label: 'admin disables buyer',
        });
        await api('/api/v1/auth/login', {
            method: 'POST',
            body: { email: buyer.email, password: buyer.password },
            expectedStatuses: [403],
            label: 'disabled account login',
        });
        await api(`/api/v1/auth/admin/enable-user?email=${encodeURIComponent(buyer.email)}`, {
            method: 'POST',
            actor: state.admin,
            expectedStatuses: [204],
            label: 'admin re-enables buyer',
        });
        buyer = await login(buyer.email, buyer.password, 'BUYER');
        state.users.set(buyer.email, buyer);
    });

    await runCheck('2FA-001', 'TOTP setup, wrong code rejection, login challenge, and disable flow', async () => {
        const setup = await api('/api/v1/auth/2fa/setup', {
            method: 'POST',
            actor: buyer,
            body: { email: buyer.email },
            expectedStatuses: [200],
            label: '2FA setup',
        });
        const secret = setup.payload?.secret;
        assert(secret, '2FA setup did not return a TOTP secret', setup.payload);

        await api('/api/v1/auth/2fa/verify', {
            method: 'POST',
            actor: buyer,
            body: { email: buyer.email, code: '000000' },
            expectedStatuses: [400],
            label: '2FA wrong setup code',
        });
        await api('/api/v1/auth/2fa/verify', {
            method: 'POST',
            actor: buyer,
            body: { email: buyer.email, code: totp(secret) },
            expectedStatuses: [200],
            label: '2FA correct setup code',
        });

        const challenged = await api('/api/v1/auth/login', {
            method: 'POST',
            body: { email: buyer.email, password: buyer.password },
            expectedStatuses: [200],
            label: '2FA login challenge',
        });
        assert(challenged.payload?.challengeToken, '2FA login did not return challenge token', challenged.payload);
        await api('/api/v1/auth/2fa/login-verify', {
            method: 'POST',
            body: { challengeToken: challenged.payload.challengeToken, code: '000000' },
            expectedStatuses: [401],
            label: '2FA wrong challenge code',
        });

        const challengedAgain = await api('/api/v1/auth/login', {
            method: 'POST',
            body: { email: buyer.email, password: buyer.password },
            expectedStatuses: [200],
            label: '2FA login challenge retry',
        });
        const verified = await api('/api/v1/auth/2fa/login-verify', {
            method: 'POST',
            body: { challengeToken: challengedAgain.payload.challengeToken, code: totp(secret) },
            expectedStatuses: [200],
            label: '2FA correct challenge code',
        });
        const actor = actorFromLoginPayload(verified.payload, 'BUYER', buyer.email, buyer.password);
        await api('/api/v1/auth/2fa/disable', {
            method: 'POST',
            actor,
            body: { email: buyer.email, code: totp(secret) },
            expectedStatuses: [204],
            label: '2FA disable',
        });
        buyer = await login(buyer.email, buyer.password, 'BUYER');
        state.users.set(buyer.email, buyer);
    });

    await runCheck('RBAC-001', 'runtime RBAC rejects unknown permissions and blocks buyer admin access', async () => {
        await api('/api/v1/auth/roles', {
            method: 'POST',
            actor: state.admin,
            body: { name: `${config.syntheticPrefix}-invalid-role`, permissions: ['missing:permission'] },
            expectedStatuses: [400],
            label: 'reject unknown permission role',
        });
        await api('/api/v1/auth/admin/users', {
            actor: buyer,
            expectedStatuses: [403],
            label: 'buyer cannot list admin users',
        });
    });

    await runCheck('WALLET-001', 'wallet split balance, top-up idempotency, withdrawal idempotency, and history', async () => {
        await ensureWallet(buyer);
        await topUpWithIdempotency(buyer, 'wallet-topup-buyer');
        await createWithdrawalWithIdempotency(buyer, 'wallet-withdraw-buyer');
        const detail = await ensureWallet(buyer);
        assert(walletActiveBalance(detail) >= 0, 'wallet active balance is invalid', detail);
        assert(walletHeldBalance(detail) >= 0, 'wallet held balance is invalid', detail);
        assert(Array.isArray(detail.history), 'wallet detail did not include audit history', detail);
    });

    await runCheck('SELLER-001', 'seller listing lifecycle and catalogue validation rules', async () => {
        await api('/api/v1/catalogue/listings', {
            method: 'POST',
            actor: seller,
            body: { description: 'missing title', startingPrice: 0 },
            expectedStatuses: [400],
            label: 'listing missing required fields',
        });
        await api('/api/v1/catalogue/listings', {
            method: 'POST',
            actor: seller,
            body: buildListingBody({ reservePrice: 100, startingPrice: 500 }),
            expectedStatuses: [400],
            label: 'reserve below start rejected',
        });
        await api('/api/v1/catalogue/listings', {
            method: 'POST',
            actor: seller,
            body: buildListingBody({ imageUrl: 'javascript:alert(1)' }),
            expectedStatuses: [400],
            label: 'unsafe image URL rejected',
        });
    });

    const listing = await runCheck('AUCTION-SETUP-001', 'seller can create, publish, and open a synthetic auction', async () => {
        const created = await createListing(seller);
        await publishListing(seller, created.id);
        await sleep(2_000);
        await openAuction(seller, created.id);
        return created;
    });

    await runCheck('BID-001', 'happy path bid creates wallet hold and updates catalogue display price', async () => {
        await topUpWithIdempotency(outbidBuyer, 'wallet-topup-outbid');
        const bid = await placeBid(buyer, listing.id, 505, [201]);
        assert(bid.payload?.id, 'accepted bid did not return bid id', bid.payload);
        await waitForListingPrice(listing.id, 505);
        const detail = await ensureWallet(buyer);
        assert(walletHeldBalance(detail) > 0, 'accepted bid did not create a held balance', detail);
    });

    await runCheck('BID-EDGE-001', 'bid validation rejects equal, low, self, inactive, and insufficient bids', async () => {
        await placeBid(outbidBuyer, listing.id, 505, [400, 409]);
        await placeBid(outbidBuyer, listing.id, 100, [400]);
        await placeBid(seller, listing.id, 510, [400, 403]);
        const draft = await createListing(seller, { title: syntheticTitle('draft-validation') });
        await placeBid(outbidBuyer, draft.id, 510, [403, 404]);
    });

    await runCheck('BID-RACE-001', 'concurrent equal bids produce exactly one accepted result for one increment', async () => {
        const raceListing = await createListing(seller, { title: syntheticTitle('race') });
        await publishListing(seller, raceListing.id);
        await sleep(2_000);
        await openAuction(seller, raceListing.id);
        const responses = await Promise.allSettled(
            Array.from({ length: config.concurrencyFanout }, (_, index) =>
                placeBid(index % 2 === 0 ? buyer : outbidBuyer, raceListing.id, 505, [201, 400, 409])
            )
        );
        const fulfilled = responses.filter((item) => item.status === 'fulfilled').map((item) => item.value);
        const accepted = fulfilled.filter((item) => item.status === 201);
        const rejected = fulfilled.filter((item) => item.status !== 201);
        assert(accepted.length === 1, 'concurrent equal bids must accept exactly one bid', {
            accepted: accepted.length,
            rejected: rejected.length,
            total: responses.length,
        });
    });

    await runCheck('ANTI-SNIPE-001', 'bid inside final window extends auction end time', async () => {
        const antiSnipeListing = await createListing(seller, { title: syntheticTitle('anti-snipe') });
        await publishListing(seller, antiSnipeListing.id);
        await sleep(2_000);
        const session = await openAuction(seller, antiSnipeListing.id, { lifetimeSeconds: 3 });
        await sleep(2_500);
        await placeBid(buyer, antiSnipeListing.id, 505, [201]);
        const after = await api(`/api/v1/listings/${encodeURIComponent(antiSnipeListing.id)}`, {
            actor: buyer,
            expectedStatuses: [200],
            label: 'read anti-snipe session',
        });
        const observedEnd = Number(after.payload?.endTime ?? after.payload?.end_time ?? 0);
        assert(observedEnd >= session.endTime, 'anti-snipe bid did not preserve or extend end time', {
            beforeEndTime: session.endTime,
            payload: after.payload,
        });
    });

    await runCheck('LIFECYCLE-001', 'auction closes to WON and settlement creates order and notification evidence', async () => {
        const closed = await closeAfterEnd(seller, listing.id);
        const status = String(closed?.status || '').toUpperCase();
        assert(['WON', 'CLOSED'].includes(status), 'expected auction to close on win path', closed);
        await waitForBuyerOrder(buyer, listing.id);
        await waitForNotification(buyer, listing.id);
    });

    await runCheck('SEC-001', 'JWT tampering, missing token, and XSS search payloads are rejected or escaped safely', async () => {
        await api('/api/v1/auth/profile', {
            headers: { Authorization: 'Bearer definitely-not-a-jwt' },
            expectedStatuses: [401],
            label: 'malformed JWT',
        });
        await api('/api/v1/auth/profile', {
            expectedStatuses: [401],
            label: 'missing JWT',
        });
        await api('/api/v1/catalogue/listings/search?keyword=%3Cscript%3Ealert(1)%3C%2Fscript%3E&page=0&size=5', {
            expectedStatuses: [200],
            label: 'XSS search payload',
        });
    });

    await runCheck('API-CONTRACT-001', 'core API error responses stay structured enough for deployment evidence', async () => {
        const response = await api('/api/v1/wallet/not-a-real-user/withdrawals', {
            method: 'POST',
            body: { amountCents: 1_000, bankCode: 'bca', accountNumber: '000000' },
            expectedStatuses: [400, 404],
            label: 'wallet structured error',
        });
        assert(
            typeof response.payload === 'object'
            && (response.payload.error || response.payload.errorCode || response.payload.message || response.payload.error_code),
            'wallet error response was not structured',
            response.payload
        );
    });

    await cleanupSyntheticData();
    writeRunLog('passed');

    console.log('');
    console.log('BidMart full spec regression passed.');
}

async function provisionSyntheticActor(tag, role) {
    const email = syntheticEmail(tag);
    const password = syntheticPassword(tag);
    await registerRaw(email, password);
    await verifySyntheticUser(email);
    let actor = await login(email, password, role);
    await api('/api/v1/auth/onboarding', {
        method: 'PUT',
        actor,
        body: {
            displayName: `${config.syntheticPrefix}-${role.toLowerCase()}`,
            shippingAddress: `${config.syntheticPrefix} synthetic address`,
            role,
        },
        expectedStatuses: [200],
        label: `${role} onboarding`,
    });
    actor = await login(email, password, role);
    actor.password = password;
    state.users.set(email, actor);
    return actor;
}

async function registerRaw(email, password) {
    state.syntheticEmails.add(email);
    await api('/api/v1/auth/register', {
        method: 'POST',
        body: { email, password },
        expectedStatuses: [200, 201],
        label: `register ${email}`,
    });
}

async function verifySyntheticUser(email, expectedStatuses = [204]) {
    const result = await api(`/api/v1/auth/admin/synthetic-users/${encodeURIComponent(email)}/verify`, {
        method: 'POST',
        actor: state.admin,
        headers: { 'X-Internal-Service-Token': config.internalToken },
        expectedStatuses: [...expectedStatuses, 404],
        label: `verify synthetic ${email}`,
    });
    if (result.status === 404 && !expectedStatuses.includes(404)) {
        throw new RegressionError(`Synthetic verification endpoint is unavailable for ${config.environment}.`, {
            email,
            status: result.status,
            payload: result.payload,
            requiredAuthCode: 'POST /api/v1/auth/admin/synthetic-users/{email}/verify',
            requiredAuthConfig: 'BIDMART_SYNTHETIC_TESTS_ENABLED=true',
            hint: 'Deploy the auth service version containing the synthetic verification endpoint and enable synthetic tests in that environment.',
        });
    }
    return result;
}

async function login(email, password, role) {
    if (!email || !password) {
        throw new RegressionError('Missing admin credentials for full regression.', {
            required: ['BIDMART_E2E_ADMIN_EMAIL', 'BIDMART_E2E_ADMIN_PASSWORD'],
        });
    }
    const loginResult = await api('/api/v1/auth/login', {
        method: 'POST',
        body: { email, password },
        expectedStatuses: [200],
        label: `${role} login`,
    });
    const payload = loginResult.payload;
    if (payload?.challengeToken) {
        throw new RegressionError(`${role} login unexpectedly requires 2FA for regression orchestration.`, {
            email,
            payload,
        });
    }
    return actorFromLoginPayload(payload, role, email, password);
}

function actorFromLoginPayload(payload, role, email, password) {
    assert(payload?.accessToken && payload?.user?.id, `${role} login did not issue token and user id`, payload);
    const actor = {
        role,
        token: payload.accessToken,
        refreshToken: payload.refreshToken,
        email,
        password,
        user: payload.user,
    };
    const roleNames = Array.isArray(payload.user.roles)
        ? payload.user.roles.map((item) => String(item.name || item).toUpperCase())
        : [];
    if (role !== 'ADMIN' && roleNames.length > 0 && !roleNames.includes(role)) {
        throw new RegressionError(`${email} does not have expected ${role} role.`, { roleNames });
    }
    return actor;
}

async function ensureWallet(actor) {
    const detail = await api(`/api/v1/wallet/${encodeURIComponent(actor.user.id)}/detail`, {
        actor,
        expectedStatuses: [200, 404],
        label: `${actor.role} wallet detail`,
    });
    if (detail.status === 200) {
        return normalizeWalletDetail(detail.payload);
    }

    await api('/api/v1/wallet/add', {
        method: 'POST',
        actor,
        body: {
            userId: actor.user.id,
            role: actor.role === 'SELLER' ? 'SELLER' : 'BUYER',
            activeBalance: 0,
            heldBalance: 0,
        },
        expectedStatuses: [200, 201],
        label: `${actor.role} create wallet`,
    });

    const created = await api(`/api/v1/wallet/${encodeURIComponent(actor.user.id)}/detail`, {
        actor,
        expectedStatuses: [200],
        label: `${actor.role} wallet detail after create`,
    });
    return normalizeWalletDetail(created.payload);
}

async function topUpWithIdempotency(actor, tag) {
    await ensureWallet(actor);
    const key = `${config.syntheticPrefix}-${tag}`;
    const first = await api(`/api/v1/wallet/${encodeURIComponent(actor.user.id)}/top-up/intent`, {
        method: 'POST',
        actor,
        headers: { 'Idempotency-Key': key },
        body: { amountCents: config.topUpCents, role: actor.role === 'SELLER' ? 'SELLER' : 'BUYER' },
        expectedStatuses: [201],
        label: `${actor.role} top-up intent`,
    });
    const second = await api(`/api/v1/wallet/${encodeURIComponent(actor.user.id)}/top-up/intent`, {
        method: 'POST',
        actor,
        headers: { 'Idempotency-Key': key },
        body: { amountCents: config.topUpCents, role: actor.role === 'SELLER' ? 'SELLER' : 'BUYER' },
        expectedStatuses: [201],
        label: `${actor.role} duplicate top-up intent`,
    });
    assert(first.payload?.paymentId === second.payload?.paymentId, 'top-up idempotency returned a different payment id', {
        first: first.payload,
        second: second.payload,
    });
    await api(`/api/v1/wallet/midtrans/payments/${encodeURIComponent(first.payload.paymentId)}/simulate`, {
        method: 'POST',
        actor,
        body: { status: 'PAID' },
        expectedStatuses: [200],
        label: `${actor.role} top-up payment simulation`,
    });
    return first.payload;
}

async function createWithdrawalWithIdempotency(actor, tag) {
    const key = `${config.syntheticPrefix}-${tag}`;
    const first = await api(`/api/v1/wallet/${encodeURIComponent(actor.user.id)}/withdrawals`, {
        method: 'POST',
        actor,
        headers: { 'Idempotency-Key': key },
        body: { amountCents: 10_000, bankCode: 'bca', accountNumber: '0000000000', role: 'BUYER' },
        expectedStatuses: [201],
        label: `${actor.role} withdrawal`,
    });
    const second = await api(`/api/v1/wallet/${encodeURIComponent(actor.user.id)}/withdrawals`, {
        method: 'POST',
        actor,
        headers: { 'Idempotency-Key': key },
        body: { amountCents: 10_000, bankCode: 'bca', accountNumber: '0000000000', role: 'BUYER' },
        expectedStatuses: [201],
        label: `${actor.role} duplicate withdrawal`,
    });
    assert(
        first.payload?.withdrawalId === second.payload?.withdrawalId,
        'withdrawal idempotency returned a different withdrawal id',
        { first: first.payload, second: second.payload }
    );
    return first.payload;
}

async function createListing(seller, overrides = {}) {
    const body = buildListingBody(overrides);
    const result = await api('/api/v1/catalogue/listings', {
        method: 'POST',
        actor: seller,
        body,
        expectedStatuses: [200, 201],
        label: 'create catalogue listing',
    });
    const id = String(result.payload?.id || '');
    assert(id, 'listing creation did not return id', result.payload);
    state.listingIds.add(id);
    return { id, payload: result.payload };
}

async function publishListing(seller, listingId) {
    await api(`/api/v1/catalogue/listings/${encodeURIComponent(listingId)}/publish`, {
        method: 'POST',
        actor: seller,
        expectedStatuses: [200, 204],
        label: 'publish listing',
    });
}

async function openAuction(seller, listingId, options = {}) {
    const now = Date.now();
    const lifetimeSeconds = options.lifetimeSeconds ?? config.auctionLifetimeSeconds;
    const startTime = Math.floor(now / 1000) - 1;
    const endTime = Math.floor((now + lifetimeSeconds * 1_000) / 1000);
    const result = await api('/api/v1/listings', {
        method: 'POST',
        actor: seller,
        body: {
            listingId,
            sellerId: seller.user.id,
            auctionType: options.auctionType || 'ENGLISH',
            starting_price_cents: 50_000,
            reserve_price_cents: 50_000,
            minimum_increment_cents: 500,
            startTime,
            endTime,
        },
        expectedStatuses: [201, 409],
        label: 'open auction session',
    });
    return { endTime, payload: result.payload };
}

async function placeBid(actor, listingId, bidAmount, expectedStatuses = [201]) {
    return api(`/api/v1/listings/${encodeURIComponent(listingId)}/bids`, {
        method: 'POST',
        actor,
        body: {
            bidderId: actor.user.id,
            bidAmount,
        },
        expectedStatuses,
        label: `${actor.role} place bid ${bidAmount}`,
    });
}

async function waitForListingPrice(listingId, expectedPrice) {
    return poll(async () => {
        const result = await api(`/api/v1/catalogue/listings/${encodeURIComponent(listingId)}`, {
            expectedStatuses: [200],
            label: 'catalogue listing detail',
        });
        const currentPrice = Number(result.payload?.currentPrice);
        if (Number.isFinite(currentPrice) && currentPrice >= expectedPrice) {
            return result.payload;
        }
        return null;
    }, 'catalogue price sync');
}

async function closeAfterEnd(seller, listingId) {
    return poll(async () => {
        const result = await api(`/api/v1/listings/${encodeURIComponent(listingId)}/close`, {
            method: 'POST',
            actor: seller,
            expectedStatuses: [200, 400],
            label: 'close auction session',
        });
        if (result.status === 400) {
            return null;
        }
        return result.payload;
    }, 'auction close after end');
}

async function waitForBuyerOrder(buyer, listingId) {
    return poll(async () => {
        const result = await api('/api/v1/orders', {
            actor: buyer,
            expectedStatuses: [200],
            label: 'buyer orders',
        });
        const orders = Array.isArray(result.payload) ? result.payload : result.payload?.orders || [];
        return orders.find((order) => String(order.auctionId || order.listingId || '') === listingId) || null;
    }, 'buyer order after auction win');
}

async function waitForNotification(actor, marker) {
    return poll(async () => {
        const result = await api('/api/v1/notifications', {
            actor,
            expectedStatuses: [200],
            label: 'notification inbox',
        });
        const notifications = Array.isArray(result.payload)
            ? result.payload
            : result.payload?.notifications || [];
        return notifications.find((notification) => {
            const message = `${notification.title || ''} ${notification.message || ''}`;
            return message.includes(marker);
        }) || null;
    }, 'notification inbox evidence');
}

async function cleanupSyntheticData() {
    if (!state.admin?.token) {
        return;
    }

    for (const listingId of state.listingIds) {
        await api(`/api/v1/catalogue/listings/${encodeURIComponent(listingId)}/cancel`, {
            method: 'POST',
            actor: state.admin,
            expectedStatuses: [200, 204, 400, 403, 404, 409],
            label: `best-effort cancel listing ${listingId}`,
        }).catch(() => null);
        await api(`/api/v1/listings/${encodeURIComponent(listingId)}/cancel`, {
            method: 'POST',
            actor: state.admin,
            expectedStatuses: [200, 204, 400, 403, 404, 409],
            label: `best-effort cancel auction ${listingId}`,
        }).catch(() => null);
    }

    for (const email of state.syntheticEmails) {
        await api(`/api/v1/auth/admin/disable-user?email=${encodeURIComponent(email)}`, {
            method: 'POST',
            actor: state.admin,
            expectedStatuses: [204, 404],
            label: `disable synthetic user ${email}`,
        }).catch(() => null);
    }
}

async function runCheck(id, name, action) {
    const started = Date.now();
    process.stdout.write(`- ${id} ${name} ... `);
    try {
        const result = await action();
        const durationMs = Date.now() - started;
        results.push({ id, name, status: 'passed', durationMs });
        console.log(`OK (${durationMs}ms)`);
        return result;
    } catch (error) {
        const durationMs = Date.now() - started;
        results.push({
            id,
            name,
            status: 'failed',
            durationMs,
            error: serializeError(error),
        });
        console.log('FAILED');
        throw error;
    }
}

async function api(pathname, options = {}) {
    return request(`${config.gatewayUrl}${pathname}`, options);
}

async function request(url, options = {}) {
    const {
        method = 'GET',
        actor,
        body,
        expectedStatuses,
        headers: extraHeaders = {},
        label = `${method} ${url}`,
    } = options;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), config.requestTimeoutMs);
    const headers = {
        Accept: 'application/json',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(actor?.token ? { Authorization: `Bearer ${actor.token}` } : {}),
        ...extraHeaders,
    };

    let response;
    try {
        response = await fetch(url, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
            signal: controller.signal,
        });
    } catch (error) {
        throw new RegressionError(`${label} could not reach ${url}.`, {
            cause: error?.message || String(error),
        });
    } finally {
        clearTimeout(timeout);
    }

    const payload = await readResponsePayload(response);
    const accepted = expectedStatuses ? expectedStatuses.includes(response.status) : response.ok;
    if (!accepted) {
        throw new RegressionError(`${label} returned HTTP ${response.status}.`, {
            status: response.status,
            payload,
            url,
        });
    }

    return { status: response.status, headers: response.headers, payload };
}

async function poll(action, label) {
    const deadline = Date.now() + config.pollTimeoutMs;
    let lastError = null;
    while (Date.now() <= deadline) {
        try {
            const result = await action();
            if (result) {
                return result;
            }
        } catch (error) {
            lastError = error;
        }
        await sleep(config.pollIntervalMs);
    }
    if (lastError) {
        throw lastError;
    }
    throw new RegressionError(`Timed out waiting for ${label}.`, { timeoutMs: config.pollTimeoutMs });
}

async function readResponsePayload(response) {
    const text = await response.text();
    if (!text) {
        return null;
    }
    try {
        return JSON.parse(text);
    } catch {
        return text.length > 500 ? `${text.slice(0, 500)}...` : text;
    }
}

function buildListingBody(overrides = {}) {
    const now = Date.now();
    return {
        title: overrides.title || syntheticTitle('listing'),
        description: overrides.description || `${config.syntheticPrefix} synthetic listing`,
        category: overrides.category || 'Electronics',
        startingPrice: overrides.startingPrice ?? 500,
        reservePrice: overrides.reservePrice ?? 500,
        minimumIncrement: overrides.minimumIncrement ?? 5,
        currentPrice: overrides.currentPrice ?? 500,
        startTime: overrides.startTime || toCatalogueDateTime(now + 1_500),
        endTime: overrides.endTime || toCatalogueDateTime(now + (config.auctionLifetimeSeconds + 10) * 1_000),
        imageUrl: overrides.imageUrl ?? null,
    };
}

function normalizeWalletDetail(payload) {
    return {
        wallet: payload?.wallet || payload,
        history: payload?.history || [],
        unpaidPayments: payload?.unpaidPayments || payload?.unpaid_payments || [],
    };
}

function walletActiveBalance(detail) {
    const value = Number(detail?.wallet?.activeBalance ?? detail?.wallet?.active_balance);
    if (!Number.isFinite(value)) {
        throw new RegressionError('Wallet detail missing active balance.', detail);
    }
    return value;
}

function walletHeldBalance(detail) {
    const value = Number(detail?.wallet?.heldBalance ?? detail?.wallet?.held_balance);
    if (!Number.isFinite(value)) {
        throw new RegressionError('Wallet detail missing held balance.', detail);
    }
    return value;
}

function totp(secret, counter = Math.floor(Date.now() / 1000 / 30)) {
    const key = decodeBase32(secret);
    const buffer = Buffer.alloc(8);
    buffer.writeBigUInt64BE(BigInt(counter));
    const hmac = crypto.createHmac('sha1', key).update(buffer).digest();
    const offset = hmac[hmac.length - 1] & 0x0f;
    const binary = ((hmac[offset] & 0x7f) << 24)
        | ((hmac[offset + 1] & 0xff) << 16)
        | ((hmac[offset + 2] & 0xff) << 8)
        | (hmac[offset + 3] & 0xff);
    return String(binary % 1_000_000).padStart(6, '0');
}

function decodeBase32(value) {
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
    let buffer = 0;
    let bitsLeft = 0;
    const bytes = [];
    for (const character of String(value).replace(/=+$/, '').toUpperCase()) {
        const index = alphabet.indexOf(character);
        if (index < 0) {
            throw new RegressionError('Invalid base32 TOTP secret returned by API.');
        }
        buffer = (buffer << 5) | index;
        bitsLeft += 5;
        if (bitsLeft >= 8) {
            bytes.push((buffer >> (bitsLeft - 8)) & 0xff);
            bitsLeft -= 8;
        }
    }
    return Buffer.from(bytes);
}

function writeRunLog(status, error = null) {
    const output = {
        status,
        environment: config.environment,
        runId: config.runId,
        gatewayUrl: config.gatewayUrl,
        frontendUrl: config.frontendUrl,
        syntheticPrefix: config.syntheticPrefix,
        startedAt: startedAt.toISOString(),
        finishedAt: new Date().toISOString(),
        results,
        error: error ? serializeError(error) : null,
    };
    fs.mkdirSync(config.logDir, { recursive: true });
    const file = path.join(config.logDir, `spec-regression-${config.environment}-${config.runId}.json`);
    fs.writeFileSync(file, `${JSON.stringify(output, null, 2)}\n`, 'utf8');
    console.log(`Regression log: ${file}`);
}

function validateSafety() {
    const prodLike = config.environment === 'prod' || config.environment === 'production';
    if (prodLike && !config.allowProductionSynthetic) {
        throw new RegressionError('Refusing production regression without BIDMART_E2E_ALLOW_PROD_SYNTHETIC=1.', {
            environment: config.environment,
            gatewayUrl: config.gatewayUrl,
        });
    }
    if (config.scope === 'full' && (!config.adminEmail || !config.adminPassword)) {
        throw new RegressionError('Full regression requires admin credentials.', {
            required: ['BIDMART_E2E_ADMIN_EMAIL', 'BIDMART_E2E_ADMIN_PASSWORD'],
        });
    }
    if (!config.syntheticPrefix.startsWith('bidmart-e2e-')) {
        throw new RegressionError('Synthetic prefix safety check failed.', { syntheticPrefix: config.syntheticPrefix });
    }
}

function assert(condition, message, details = {}) {
    if (!condition) {
        throw new RegressionError(message, details);
    }
}

function syntheticEmail(tag) {
    return `${uniqueLocal(tag)}@e2e.bidmart.local`;
}

function uniqueLocal(tag) {
    return `${config.syntheticPrefix}-${sanitizeRunId(tag)}`;
}

function syntheticPassword(tag) {
    return `Bidmart1!${config.runId}${sanitizeRunId(tag)}`.slice(0, 72);
}

function syntheticTitle(tag) {
    return `${config.syntheticPrefix}-${sanitizeRunId(tag)}`;
}

function toCatalogueDateTime(ms) {
    return new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

function serializeError(error) {
    return {
        name: error?.name || 'Error',
        message: error?.message || String(error),
        details: error?.details || undefined,
        stack: error?.stack || undefined,
    };
}

function hasArg(name) {
    return argv.includes(name);
}

function argValue(name) {
    const index = argv.indexOf(name);
    if (index < 0) {
        return null;
    }
    return argv[index + 1] || null;
}

function envValue(name) {
    const value = env[name];
    return value && value.trim() ? value.trim() : null;
}

function positiveInt(value, fallback) {
    if (value === undefined || value === null || value === '') {
        return fallback;
    }
    const parsed = Number.parseInt(String(value), 10);
    if (!Number.isFinite(parsed) || parsed <= 0) {
        throw new RegressionError(`Expected a positive integer but got ${value}.`);
    }
    return parsed;
}

function normalizeScope(value) {
    const normalized = String(value).toLowerCase();
    if (!['public', 'full'].includes(normalized)) {
        throw new RegressionError(`Unsupported regression scope ${value}.`, { allowed: ['public', 'full'] });
    }
    return normalized;
}

function normalizeEnvironment(value) {
    const normalized = String(value || '').toLowerCase();
    if (normalized === 'production') {
        return 'prod';
    }
    return normalized;
}

function inferEnvironment(gatewayUrl) {
    const lowered = gatewayUrl.toLowerCase();
    if (lowered.includes('prod')) {
        return 'prod';
    }
    if (lowered.includes('staging')) {
        return 'staging';
    }
    return 'local';
}

function sanitizeRunId(value) {
    return String(value)
        .toLowerCase()
        .replace(/[^a-z0-9-]/g, '-')
        .replace(/-+/g, '-')
        .replace(/^-|-$/g, '')
        .slice(0, 48) || 'run';
}

function stripTrailingSlash(value) {
    return String(value).replace(/\/+$/, '');
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function printTraceIds() {
    console.log([
        'API-001 gateway/catalogue/frontend reachability',
        'REG-001 registration validation and verification guard',
        'AUTH-001 login/session/refresh/deactivation',
        '2FA-001 TOTP 2FA lifecycle',
        'RBAC-001 runtime permissions',
        'WALLET-001 wallet balance/idempotency/audit',
        'SELLER-001 listing lifecycle validation',
        'BID-001 bid happy path',
        'BID-EDGE-001 bid validation edge cases',
        'BID-RACE-001 concurrent equal-bid race',
        'ANTI-SNIPE-001 final-window extension',
        'LIFECYCLE-001 close/win/order/notification',
        'SEC-001 JWT and XSS safety',
        'API-CONTRACT-001 structured error contracts',
    ].join('\n'));
}

function printHelp() {
    console.log(`
BidMart spec regression

Usage:
  node scripts/spec-regression.mjs --scope full
  node scripts/spec-regression.mjs --scope public
  node scripts/spec-regression.mjs --list

Environment contract:
  BIDMART_GATEWAY_URL
  BIDMART_FRONTEND_URL
  BIDMART_E2E_ADMIN_EMAIL
  BIDMART_E2E_ADMIN_PASSWORD
  BIDMART_E2E_RUN_ID
  BIDMART_E2E_ALLOW_PROD_SYNTHETIC=1

Useful options:
  BIDMART_E2E_ENV=local|staging|prod
  BIDMART_E2E_INTERNAL_TOKEN=...
  BIDMART_REGRESSION_CONCURRENCY=100
  BIDMART_REGRESSION_LOG_DIR=spec-regression-results
  BIDMART_SKIP_FRONTEND_CHECK=1
`);
}
