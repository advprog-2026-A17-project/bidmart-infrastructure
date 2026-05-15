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

    await step('auction listing route is reachable through the gateway', () => {
        return api('/api/v1/auctions', {
            expectedStatuses: [200, 401, 403],
            label: 'unauthenticated auction listing route',
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

    await step('authenticated auction listing endpoint is reachable', () => {
        return api('/api/v1/auctions', {
            actor: buyer,
            label: 'authenticated auction listing',
        });
    });

    await step('buyer wallet exists', () => ensureWallet(buyer));
    await step('buyer wallet can be topped up through sandbox intent', () => topUpBuyerWallet(buyer));

    const listing = await step('seller can create a catalogue listing', () => createListing(seller));
    await step('seller can publish the listing', () => publishListing(seller, listing.id));

    const auction = await step('seller can create an English auction', () => createAuction(seller, listing.id));
    await step('catalogue can mark the listing auction-created', () => markAuctionCreated(seller, listing.id));

    await step('buyer can place a bid backed by wallet hold', () => placeBid(buyer, auction.id));
    await step('auction can close after its end time', () => closeAuctionAfterEnd(seller, auction.id));

    await step('buyer wallet detail remains readable after auction lifecycle', () => ensureWallet(buyer));

    if (!config.skipNotifications) {
        await step('buyer receives an auction notification', () => waitForAuctionNotification(buyer, auction.id));
    }

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
    console.log(`Created auction: ${state.createdAuctionId}`);
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
    const result = await api('/api/v1/catalogue/listings', {
        method: 'POST',
        actor: seller,
        body: {
            title: `Functional smoke listing ${suffix}`,
            description: 'Created by scripts/functional-smoke.mjs',
            category: 'Electronics',
            startingPrice: 500,
            reservePrice: 500,
            currentPrice: 500,
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

async function createAuction(seller, listingId) {
    const now = Date.now();
    const startTime = new Date(now - 1_000).toISOString();
    const endTime = new Date(now + config.auctionLifetimeSeconds * 1_000).toISOString();

    const result = await api('/api/v1/auctions', {
        method: 'POST',
        actor: seller,
        body: {
            listingId,
            sellerId: seller.user.id,
            auctionType: 'ENGLISH',
            startingPrice: 500,
            reservePrice: 500,
            minimumIncrement: 5,
            startTime,
            endTime,
        },
        expectedStatuses: [201],
        label: 'create auction',
    });

    const auctionId = String(result.payload?.id || '');
    if (!auctionId) {
        throw new SmokeError('Auction create did not return an id.', { payload: result.payload });
    }
    state.createdAuctionId = auctionId;
    return { id: auctionId, endTime, payload: result.payload };
}

async function markAuctionCreated(seller, listingId) {
    return api(`/api/v1/catalogue/listings/${encodeURIComponent(listingId)}/auction-created`, {
        method: 'POST',
        actor: seller,
        label: 'mark auction created',
    });
}

async function placeBid(buyer, auctionId) {
    const result = await api(`/api/v1/auctions/${encodeURIComponent(auctionId)}/bids`, {
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

async function closeAuctionAfterEnd(seller, auctionId) {
    return poll(async () => {
        try {
            const result = await api(`/api/v1/auctions/${encodeURIComponent(auctionId)}/close`, {
                method: 'POST',
                actor: seller,
                label: 'close auction',
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
    }, 'auction close');
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
