#!/usr/bin/env node
/**
 * Automated checks for the six BidMart specification areas.
 * Requires gateway at BIDMART_GATEWAY_URL (default http://localhost:8000).
 */

const gateway = (process.env.BIDMART_GATEWAY_URL || 'http://localhost:8000').replace(/\/$/, '');

const checks = [];

async function get(path) {
    const response = await fetch(`${gateway}${path}`, { signal: AbortSignal.timeout(10_000) });
    const text = await response.text();
    let body;
    try {
        body = JSON.parse(text);
    } catch {
        body = text;
    }
    return { status: response.status, body };
}

async function main() {
    console.log(`Spec integration verify → ${gateway}`);

    const search = await get('/api/v1/catalogue/listings/search?page=0&size=1');
    checks.push({ name: 'Spec 6/3: catalogue search reachable', ok: search.status === 200 });

    const tree = await get('/api/v1/catalogue/categories/tree');
    checks.push({ name: 'Spec 3: category tree public', ok: tree.status === 200 });

    const perm = await get('/api/v1/auth/permissions/check?email=admin@bidmart.com&permission=admin:roles');
    checks.push({ name: 'Spec 1: permission check API', ok: perm.status === 200 && typeof perm.body?.allowed === 'boolean' });

    const policies = await get('/api/v1/auth/diagnostics/policies');
    checks.push({
        name: 'Spec 2: session policy diagnostics',
        ok: policies.status === 401 || (policies.status === 200 && policies.body?.concurrentSessionLimitPolicy),
    });

    let failed = 0;
    for (const check of checks) {
        const mark = check.ok ? 'PASS' : 'FAIL';
        console.log(`[${mark}] ${check.name}`);
        if (!check.ok) failed += 1;
    }

    if (failed > 0) {
        console.error(`\n${failed} check(s) failed. Start stack: cd bidmart-infrastructure && ./scripts/docker-up.sh`);
        process.exit(1);
    }
    console.log('\nAll automated spec checks passed.');
}

main().catch((error) => {
    console.error('Spec verify could not reach gateway:', error.message);
    console.error('Start backend first, then re-run this script.');
    process.exit(1);
});
