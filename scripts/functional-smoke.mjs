#!/usr/bin/env node

const DEFAULT_GATEWAY_URL = 'http://localhost:8000';
const DEFAULT_FRONTEND_URL = 'http://localhost';
const DEFAULT_REQUEST_TIMEOUT_MS = 15_000;
const DEFAULT_POLL_TIMEOUT_MS = 200_000;
const DEFAULT_POLL_INTERVAL_MS = 1_000;

class SmokeError extends Error {
    constructor(message, details = {}) {
        super(message);
        this.name = 'SmokeError';
        this.details = details;
    }
}

const env = process.env;
const args = new Set(process.argv.slice(2));

if (args.has('--help') || args.has('-h')) {
    printHelp();
    process.exit(0);
}

const config = {
    gatewayUrl: stripTrailingSlash(env.BIDMART_GATEWAY_URL || env.BIDMART_BASE_URL || DEFAULT_GATEWAY_URL),
    frontendUrl: stripTrailingSlash(env.BIDMART_FRONTEND_URL || DEFAULT_FRONTEND_URL),
    scope: normalizeScope(env.BIDMART_SMOKE_SCOPE || env.BIDMART_FUNCTIONAL_SCOPE || 'full'),
    requestTimeoutMs: positiveInt(env.BIDMART_REQUEST_TIMEOUT_MS, DEFAULT_REQUEST_TIMEOUT_MS),
    pollTimeoutMs: positiveInt(env.BIDMART_POLL_TIMEOUT_MS, DEFAULT_POLL_TIMEOUT_MS),
    pollIntervalMs: positiveInt(env.BIDMART_POLL_INTERVAL_MS, DEFAULT_POLL_INTERVAL_MS),
    auctionLifetimeSeconds: positiveInt(env.BIDMART_AUCTION_LIFETIME_SECONDS, 10),
    topUpCents: positiveInt(env.BIDMART_SMOKE_TOP_UP_CENTS, 100_000),
    skipFrontend: env.BIDMART_SKIP_FRONTEND_CHECK === '1',
    skipNotifications: env.BIDMART_SKIP_NOTIFICATION_CHECK === '1',
};

const state = {
    createdListingId: null,
    createdAuctionId: null,
};

main().catch((error) => {
    console.error('');
    console.error('Functional smoke failed.');
    if (error instanceof SmokeError) {
        console.error(error.message);
        if (Object.keys(error.details).length > 0) {
            console.error(JSON.stringify(error.details, null, 2));
        }
    } else {
        console.error(error?.stack || String(error));
    }
    process.exit(1);
});

async function main() {
    console.log('BidMart functional smoke');
    console.log(`Gateway:  ${config.gatewayUrl}`);
    console.log(`Frontend: ${config.frontendUrl}`);
    console.log(`Scope:    ${config.scope}`);
    console.log('');

    await step('public catalogue search is reachable', () => {
        return api('/api/v1/catalogue/listings/search?page=0&size=1');
    });

    await step('bidding listing route is reachable through the gateway', () => {
        return api('/api/v1/listings', {
            expectedStatuses: [200, 401, 403],
            label: 'unauthenticated bidding listing route',
        });
    });

    if (!config.skipFrontend) {
        await step('frontend shell is reachable', () => {
            return request(config.frontendUrl, { expectedStatuses: [200] });
        });
    }

    if (config.scope === 'public') {
        console.log('');
        console.log('Public smoke passed. Set BIDMART_SMOKE_SCOPE=full with seller and buyer credentials for the full flow.');
        return;
    }

    const seller = await step('seller can log in', () => authenticateActor('SELLER'));
    const buyer = await step('buyer can log in', () => authenticateActor('BUYER'));

    // Attempt to run admin-only checks when admin credentials are provided.
    let admin = null;
    try {
        admin = await step('admin can log in', async () => await authenticateActor('ADMIN'));
    } catch (err) {
        console.log('Skipping admin checks: admin credentials not provided or login failed.');
    }

    await step('authenticated bidding listing endpoint is reachable', () => {
        return api('/api/v1/listings', {
            actor: buyer,
            label: 'authenticated bidding listing',
        });
    });

    await step('buyer wallet exists', () => ensureWallet(buyer));
    await step('buyer wallet can be topped up through sandbox intent', () => topUpBuyerWallet(buyer));

    const listing = await step('seller can create a catalogue listing', () => createListing(seller));
    await step('seller can publish the listing', () => publishListing(seller, listing.id));

    const session = await step('seller can open listing auction session', () => openListingAuctionSession(seller, listing.id));
    if (session.id !== listing.id) {
        throw new SmokeError('Listing auction session id must match catalogue listing id.', {
            listingId: listing.id,
            sessionId: session.id,
        });
    }

    await step('buyer can place a bid backed by wallet hold', () => placeBid(buyer, listing.id));
    await step('catalogue reflects bid price update', () => waitForListingPrice(listing.id, 505));
    const settled = await step('listing can settle after its end time', () => closeListingAfterEnd(seller, listing.id));
    const auctionStatus = String(settled?.status || '').toUpperCase();
    if (auctionStatus !== 'WON') {
        throw new SmokeError('Expected auction to close as WON for win-path smoke.', {
            listingId: listing.id,
            status: auctionStatus,
            settled,
        });
    }

    await step('buyer wallet detail remains readable after auction lifecycle', () => ensureWallet(buyer));

    if (!config.skipNotifications) {
        await step('buyer receives an auction notification', () => waitForAuctionNotification(buyer, listing.id));
    }

    const winOrder = await step('buyer order exists after auction win', () =>
        waitForBuyerOrder(buyer, listing.id)
    );
    await step('buyer order status is CREATED after win', () => verifyWinOrderCreated(winOrder));

    // If admin credentials were provided, validate an admin-only endpoint
    if (admin) {
        await step('admin can access diagnostics', () => api('/api/v1/auth/diagnostics/policies', {
            actor: admin,
            expectedStatuses: [200],
            label: 'admin diagnostics',
        }));
    }

    console.log('');
    console.log('Functional smoke passed.');
    console.log(`Created listing: ${state.createdListingId}`);
    console.log(`Listing auction session: ${state.createdAuctionId ?? state.createdListingId}`);
}

async function authenticateActor(role) {
    const token = envValue(`BIDMART_${role}_TOKEN`);
    const userId = envValue(`BIDMART_${role}_USER_ID`);
    if (token && userId) {
        return {
            role,
            token,
            user: {
                id: userId,
                email: envValue(`BIDMART_${role}_EMAIL`) || `${role.toLowerCase()}@local`,
                roles: [{ name: role }],
            },
        };
    }

    const email = requiredEnv(`BIDMART_${role}_EMAIL`);
    const password = requiredEnv(`BIDMART_${role}_PASSWORD`);
    const login = await api('/api/v1/auth/login', {
        method: 'POST',
        body: { email, password },
        label: `${role} login`,
    });

    let payload = login.payload;
    if (payload?.challengeToken) {
        const twoFactorCode = requiredEnv(`BIDMART_${role}_2FA_CODE`);
        const twoFactor = await api('/api/v1/auth/2fa/login-verify', {
            method: 'POST',
            body: { challengeToken: payload.challengeToken, code: twoFactorCode },
            label: `${role} 2FA verification`,
        });
        payload = twoFactor.payload;
    }

    if (!payload?.accessToken || !payload?.user?.id) {
        throw new SmokeError(`${role} login did not return an access token and user id.`, { payload });
    }

    assertRole(payload.user, role);
    return {
        role,
        token: payload.accessToken,
        user: payload.user,
    };
}

// If admin is available, validate an admin-only endpoint
if (typeof main !== 'undefined') {
    // The main flow will call admin-specific checks where appropriate.
}

async function ensureWallet(actor) {
    const detailPath = `/api/v1/wallet/${encodeURIComponent(actor.user.id)}/detail`;
    const detail = await api(detailPath, {
        actor,
        expectedStatuses: [200, 404],
        label: `${actor.role} wallet detail`,
    });

    if (detail.status === 200) {
        return detail.payload;
    }

    await api('/api/v1/wallet/add', {
        method: 'POST',
        actor,
        body: {
            userId: actor.user.id,
            activeBalance: 0,
            heldBalance: 0,
        },
        label: `${actor.role} wallet create`,
    });

    const createdDetail = await api(detailPath, {
        actor,
        label: `${actor.role} wallet detail after create`,
    });
    return createdDetail.payload;
}

async function topUpBuyerWallet(buyer) {
    const before = await ensureWallet(buyer);
    const beforeBalance = walletActiveBalance(before);

    const intent = await api(`/api/v1/wallet/${encodeURIComponent(buyer.user.id)}/top-up/intent`, {
        method: 'POST',
        actor: buyer,
        body: { amountCents: config.topUpCents },
        expectedStatuses: [201],
        label: 'top-up intent',
    });

    const paymentId = intent.payload?.paymentId;
    if (!paymentId || intent.payload?.status !== 'PENDING') {
        throw new SmokeError('Top-up intent did not return a pending payment.', { payload: intent.payload });
    }

    await api(`/api/v1/wallet/midtrans/payments/${encodeURIComponent(paymentId)}/simulate`, {
        method: 'POST',
        actor: buyer,
        body: { status: 'PAID' },
        label: 'settle top-up payment',
    });

    const after = await ensureWallet(buyer);
    const afterBalance = walletActiveBalance(after);
    if (afterBalance < beforeBalance + config.topUpCents) {
        throw new SmokeError('Wallet balance did not increase after paid top-up.', {
            beforeBalance,
            afterBalance,
            topUpCents: config.topUpCents,
        });
    }

    return { paymentId, beforeBalance, afterBalance };
}

async function createListing(seller) {
    const suffix = Date.now();
    const now = Date.now();
    const startTime = new Date(now - 1_000).toISOString();
    const endTime = new Date(now + config.auctionLifetimeSeconds * 1_000).toISOString();
    const result = await api('/api/v1/catalogue/listings', {
        method: 'POST',
        actor: seller,
        body: {
            title: `Functional smoke listing ${suffix}`,
            description: 'Created by scripts/functional-smoke.mjs',
            category: 'Electronics',
            startingPrice: 500,
            reservePrice: 500,
            minimumIncrement: 5,
            currentPrice: 500,
            startTime,
            endTime,
            imageUrl: null,
        },
        label: 'create listing',
    });

    const listingId = String(result.payload?.id || '');
    if (!listingId) {
        throw new SmokeError('Listing create did not return an id.', { payload: result.payload });
    }
    state.createdListingId = listingId;
    return { id: listingId, payload: result.payload };
}

async function publishListing(seller, listingId) {
    return api(`/api/v1/catalogue/listings/${encodeURIComponent(listingId)}/publish`, {
        method: 'POST',
        actor: seller,
        label: 'publish listing',
    });
}

async function openListingAuctionSession(seller, listingId) {
    const now = Date.now();
    const startTime = Math.floor((now - 1_000) / 1000);
    const endTime = Math.floor((now + config.auctionLifetimeSeconds * 1_000) / 1000);

    const result = await api('/api/v1/listings', {
        method: 'POST',
        actor: seller,
        body: {
            listingId,
            sellerId: seller.user.id,
            auctionType: 'ENGLISH',
            starting_price_cents: 50_000,
            reserve_price_cents: 50_000,
            minimum_increment_cents: 500,
            startTime,
            endTime,
        },
        expectedStatuses: [201, 409],
        label: 'open listing auction session',
    });

    const sessionId = String(result.payload?.id || result.payload?.listingId || listingId);
    state.createdAuctionId = sessionId;
    return { id: sessionId, endTime, payload: result.payload };
}

async function placeBid(buyer, listingId) {
    const result = await api(`/api/v1/listings/${encodeURIComponent(listingId)}/bids`, {
        method: 'POST',
        actor: buyer,
        body: {
            bidderId: buyer.user.id,
            bidAmount: 505,
        },
        expectedStatuses: [201],
        label: 'place bid',
    });

    if (!result.payload?.id) {
        throw new SmokeError('Bid placement did not return a bid id.', { payload: result.payload });
    }
    return result.payload;
}

async function closeListingAfterEnd(seller, listingId) {
    return poll(async () => {
        try {
            const result = await api(`/api/v1/listings/${encodeURIComponent(listingId)}/close`, {
                method: 'POST',
                actor: seller,
                label: 'settle listing',
            });
            return result.payload;
        } catch (error) {
            if (error instanceof SmokeError && error.details.status === 400) {
                const message = String(error.details.payload?.message || error.details.body || '');
                if (message.includes('end time') || message.includes('not reached')) {
                    return null;
                }
            }
            throw error;
        }
    }, 'listing settlement');
}

async function waitForListingPrice(listingId, expectedPrice) {
    return poll(async () => {
        const result = await api(`/api/v1/catalogue/listings/${encodeURIComponent(listingId)}`, {
            label: 'read listing after bid',
        });
        const currentPrice = Number(result.payload?.currentPrice);
        const hasBids = Boolean(result.payload?.hasBids);
        if (hasBids && Number.isFinite(currentPrice) && currentPrice >= expectedPrice) {
            return result.payload;
        }
        return null;
    }, 'catalogue bid price sync');
}

async function waitForBuyerOrder(buyer, listingId) {
    return poll(async () => {
        const result = await api('/api/v1/orders', {
            actor: buyer,
            label: 'list buyer orders',
        });
        const orders = Array.isArray(result.payload) ? result.payload : [];
        const match = orders.find((order) => {
            const auctionId = String(order.auctionId || order.listingId || '');
            return auctionId === listingId;
        });
        return match || null;
    }, 'buyer order after win');
}

function verifyWinOrderCreated(order) {
    const status = String(order?.status || '').toUpperCase();
    if (!status) {
        throw new SmokeError('Win-path order missing status field.', { order });
    }
    if (status !== 'CREATED') {
        throw new SmokeError(`Expected win-path order status CREATED but got ${status}.`, { order });
    }
    return order;
}

async function waitForAuctionNotification(buyer, auctionId) {
    return poll(async () => {
        const result = await api('/api/v1/notifications', {
            actor: buyer,
            label: 'list notifications',
        });
        const notifications = Array.isArray(result.payload)
            ? result.payload
            : result.payload?.notifications || [];

        const match = notifications.find((notification) => {
            const message = `${notification.title || ''} ${notification.message || ''}`;
            return message.includes(auctionId);
        });

        return match || null;
    }, 'auction notification');
}

async function step(name, action) {
    const start = Date.now();
    process.stdout.write(`- ${name} ... `);
    try {
        const result = await action();
        console.log(`OK (${Date.now() - start}ms)`);
        return result;
    } catch (error) {
        console.log('FAILED');
        throw error;
    }
}

async function api(path, options = {}) {
    return request(`${config.gatewayUrl}${path}`, options);
}

async function request(url, options = {}) {
    const {
        method = 'GET',
        actor,
        body,
        expectedStatuses,
        label = `${method} ${url}`,
    } = options;

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), config.requestTimeoutMs);
    const headers = {
        Accept: 'application/json',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(actor?.token ? { Authorization: `Bearer ${actor.token}` } : {}),
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
        throw new SmokeError(`${label} could not reach ${url}.`, {
            cause: error?.message || String(error),
        });
    } finally {
        clearTimeout(timeout);
    }

    const payload = await readResponsePayload(response);
    const accepted = expectedStatuses
        ? expectedStatuses.includes(response.status)
        : response.ok;

    if (!accepted) {
        throw new SmokeError(`${label} returned HTTP ${response.status}.`, {
            status: response.status,
            payload,
            body: typeof payload === 'string' ? payload : undefined,
            url,
        });
    }

    return {
        status: response.status,
        headers: response.headers,
        payload,
    };
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
    throw new SmokeError(`Timed out waiting for ${label}.`, {
        timeoutMs: config.pollTimeoutMs,
    });
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

function walletActiveBalance(detailPayload) {
    const wallet = detailPayload?.wallet || detailPayload;
    const balance = Number(wallet?.activeBalance ?? wallet?.active_balance);
    if (!Number.isFinite(balance)) {
        throw new SmokeError('Wallet detail response did not include active balance.', { payload: detailPayload });
    }
    return balance;
}

function assertRole(user, role) {
    const roleNames = Array.isArray(user.roles)
        ? user.roles.map((item) => String(item.name || item).toUpperCase())
        : [];
    if (roleNames.length > 0 && !roleNames.includes(role)) {
        throw new SmokeError(`Expected ${user.email || user.id} to have ${role} role.`, {
            roles: roleNames,
        });
    }
}

function requiredEnv(name) {
    const value = envValue(name);
    if (!value) {
        throw new SmokeError(`Missing required environment variable ${name}.`, {
            hint: 'Use verified demo accounts, or provide BIDMART_SELLER_TOKEN/BIDMART_SELLER_USER_ID and BIDMART_BUYER_TOKEN/BIDMART_BUYER_USER_ID.',
        });
    }
    return value;
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
        throw new SmokeError(`Expected a positive integer but got ${value}.`);
    }
    return parsed;
}

function normalizeScope(value) {
    const normalized = String(value).toLowerCase();
    if (!['public', 'full'].includes(normalized)) {
        throw new SmokeError(`Unsupported smoke scope ${value}.`, {
            allowed: ['public', 'full'],
        });
    }
    return normalized;
}

function stripTrailingSlash(value) {
    return String(value).replace(/\/+$/, '');
}

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function printHelp() {
    console.log(`
BidMart functional smoke

Usage:
  node scripts/functional-smoke.mjs
  BIDMART_SMOKE_SCOPE=public node scripts/functional-smoke.mjs

Required for full scope:
  BIDMART_SELLER_EMAIL
  BIDMART_SELLER_PASSWORD
  BIDMART_BUYER_EMAIL
  BIDMART_BUYER_PASSWORD

Token alternative:
  BIDMART_SELLER_TOKEN
  BIDMART_SELLER_USER_ID
  BIDMART_BUYER_TOKEN
  BIDMART_BUYER_USER_ID

Useful options:
  BIDMART_GATEWAY_URL=http://localhost:8000
  BIDMART_FRONTEND_URL=http://localhost
  BIDMART_SMOKE_SCOPE=full|public
  BIDMART_SKIP_FRONTEND_CHECK=1
  BIDMART_SKIP_NOTIFICATION_CHECK=1
  BIDMART_AUCTION_LIFETIME_SECONDS=5
  BIDMART_SMOKE_TOP_UP_CENTS=100000
`);
}
