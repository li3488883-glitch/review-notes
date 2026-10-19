# Data for the screening page, built on GitHub Actions from BaoStock (free, meant for bulk download).
#   python tools/make_data.py shard K N MODE    -> out/shard_K.npz, out/base_K.json, cache/raw_N_K.npz   (MODE: daily | backfill | recompute)
#   python tools/make_data.py combine MODE      -> base.json, picks/YYYY.json, names.json, dashi.json
# 大势 history is not built here: the page reads 同花顺's yearly index files directly when a past day is picked.
# The rules match the page's own check (rt.js evalAt): every stock is checked on every trading day,
# and each day keeps the stocks that pass, or miss by one condition, among that day's top 200 by turnover.
import sys, os, json, time, datetime, glob, zlib
import numpy as np

POOL, KEEP, NB, NV, NO = 200, 1800, 66, 66, 11
BACKFILL_START = '2009-06-01'
PICKS_FROM = '2010-01-04'
BITS = ['ma5', 'high', 'vol', 'trend', 'burst', 'dip', 'fanbao', 'reclaim', 'newHigh', 'notLimDn', 'rs', 'kd', 'lead', 'extreme', 'tech']
INDEX = {'kc': 'sh.000688', 'cy': 'sz.399006', 'sh': 'sh.000001'}

def bjtoday():
    return (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=8)).date()

def rows(rs):
    out = []
    while rs.error_code == '0' and rs.next():
        out.append(rs.get_row_data())
    return out

def roll(a, n, fn):
    out = np.full(len(a), np.nan)
    if len(a) >= n:
        w = np.lib.stride_tricks.sliding_window_view(a, n)
        out[n - 1:] = fn(w, axis=1)
    return out

def shift(a, k):
    out = np.full(len(a), np.nan)
    if k < len(a):
        out[k:] = a[:len(a) - k]
    return out

def is_tech(code, ind):
    # 科技：科创板，或者证监会行业是电子/通信（C39）、软件和信息技术服务（I63–I65）；创业板里的专用设备、仪器仪表也算
    ind = ind or ''
    return code[:3] in ('688', '689') or ind[:3] in ('C39', 'I63', 'I64', 'I65') or (code[:3] in ('300', '301') and ind[:3] in ('C35', 'C40'))

def since_high(H):
    # days since the last bar whose high tops the 19 bars before it (looking back at most 30 bars, from bar 20 on)
    n = len(H)
    prev19 = shift(roll(H, 19, np.max), 1)
    is_hi = (H >= prev19) & (np.arange(n) >= 20)
    idx = np.where(is_hi, np.arange(n), -1)
    last = np.maximum.accumulate(idx)
    out = np.arange(n) - last
    out[(last < 0) | (out > 30)] = 30
    return out

def evaluate(code, d, O, H, L, C, V, I):
    """Same rules as the page's evalAt (rt.js). I: the stock's gate index close on each of its dates (NaN if missing)."""
    n = len(C)
    m5, m10, m20 = roll(C, 5, np.mean), roll(C, 10, np.mean), roll(C, 20, np.mean)
    A = V * C
    a10, a60 = roll(A, 10, np.mean), roll(A, 60, np.mean)
    v5 = roll(V, 5, np.mean)
    pc, m5p = shift(C, 1), shift(m5, 1)
    aprev5 = shift(roll(A, 5, np.mean), 1)
    sh = since_high(H)
    c20p = shift(roll(C, 20, np.max), 1)
    c60p = shift(roll(C, 60, np.max), 1)
    hi60 = roll(H, 60, np.max)
    with np.errstate(invalid='ignore', divide='ignore'):
        chg = C / pc - 1
        lim = np.where(np.full(n, code[:3] in ('688', '689')), 0.2, np.where(np.full(n, code[:2] == '30') & (d >= 20200824), 0.2, 0.1))
        limdn = chg <= -(lim - 0.002)
        f = {}
        f['ma5'] = C >= m5
        f['high'] = sh <= 3
        f['vol'] = a10 >= a60
        f['trend'] = (m5 > m20) & (m20 >= shift(m20, 5))
        hold = f['ma5'] & f['high'] & f['vol'] & f['trend']
        prevhold = np.r_[False, hold[:-1]]
        f['burst'] = (chg >= np.where(lim >= 0.2, 0.07, 0.05)) & (A >= 1.5 * aprev5) & ((C > c20p) | (pc < m5p)) & (C >= H * 0.97)
        f['dip'] = prevhold & (chg <= 0.02) & ((L <= m5 * 1.01) | (L <= m10 * 1.01)) & (C >= m10) & ~limdn
        o1 = shift(O, 1)
        f['fanbao'] = (pc / shift(C, 2) - 1 <= -0.03) & (pc < o1) & (C > o1) & (C >= m5)
        f['reclaim'] = (pc <= m5p) & (C > m5) & (V >= 1.5 * shift(V, 1))
        f['newHigh'] = (C > c60p) & (A >= 1.2 * aprev5) & (np.r_[0, sh[:-1]] >= 2)
        f['notLimDn'] = ~limdn
        ixok = np.isfinite(I) & np.isfinite(shift(I, 20))
        f['rs'] = ixok & (C / shift(C, 20) - 1 > I / shift(I, 20) - 1)
        ichg = I / shift(I, 1) - 1
        anti = np.nan_to_num(((ichg <= -0.01) & (chg >= -0.005)).astype(float), nan=0)
        f['kd'] = ixok & (C >= m5) & (roll(anti, 10, np.max) > 0)
        f['lead'] = ixok & (C > c60p) & (I < 0.98 * roll(np.nan_to_num(I, nan=-np.inf), 21, np.max))
        vmax = roll(np.nan_to_num(v5, nan=0.0), 500, np.max)
        vmax[:499] = np.maximum.accumulate(np.nan_to_num(v5, nan=0.0))[:499]
        f['extreme'] = (v5 >= vmax * 0.999) & (np.arange(n) >= 120)
        f['tech'] = np.zeros(n, dtype=bool)
        fails = sum((~f[k]).astype(np.int8) for k in ('ma5', 'high', 'vol', 'trend'))
        ret20 = C / shift(C, 20) - 1
    flags = np.zeros(n, dtype=np.uint16)
    for b, k in enumerate(BITS):
        flags |= (np.nan_to_num(f[k].astype(float), nan=0).astype(np.uint16) << b)
    valid = np.arange(n) >= 64
    return flags, fails.astype(np.int8), chg, ret20, valid

def state_of(flags):
    """'b' 买点 / 'h' 主升持有 / '' 都不是 — from the flag bits (same as the page)."""
    fl = lambda k: (flags >> BITS.index(k)) & 1
    trig = fl('burst') | fl('dip') | fl('fanbao') | fl('reclaim') | fl('newHigh')
    buy = trig & fl('notLimDn') & fl('ma5')
    hold = fl('ma5') & fl('high') & fl('vol') & fl('trend')
    return np.where(buy == 1, 2, np.where(hold == 1, 1, 0))

# ---------- raw-data cache ----------
# Raw daily bars are kept in a GitHub release (raw-data), one file per shard, in 后复权 prices:
# back-adjusted history never changes when a stock pays a dividend, so new days can simply be appended.
# The page needs today's real price scale (前复权), which is the same series times (real close / 后复权 close) on the last day.
FIELDS = ('d', 'o', 'h', 'l', 'c', 'v', 'a', 't', 'st')

def cache_path(k, N):
    return f'cache/raw_{N}_{k}.npz'

def load_cache(k, N):
    p = cache_path(k, N)
    if not os.path.exists(p):
        return None
    z = np.load(p, allow_pickle=False)
    ids, off = list(z['ids']), z['off']
    out = {}
    for q, sid in enumerate(ids):
        sl = slice(int(off[q]), int(off[q + 1]))
        out[str(sid)] = {f: z[f][sl] for f in FIELDS}
        out[str(sid)]['rc'] = (int(z['rcd'][q]), float(z['rc'][q]))
    print('cache loaded', p, len(out), 'stocks', flush=True)
    return out

def save_cache(k, N, store):
    os.makedirs('cache', exist_ok=True)
    ids = sorted(store)
    off = np.r_[0, np.cumsum([len(store[s]['d']) for s in ids])].astype(np.int64)
    arr = {}
    for f in FIELDS:
        parts = [store[s][f] for s in ids]
        dt = np.int32 if f == 'd' else bool if f == 'st' else np.float64
        arr[f] = np.concatenate(parts).astype(dt) if parts else np.array([], dtype=dt)
    np.savez_compressed(cache_path(k, N), ids=np.array(ids, dtype='U9'), off=off,
                        rcd=np.array([store[s]['rc'][0] for s in ids], dtype=np.int64),
                        rc=np.array([store[s]['rc'][1] for s in ids], dtype=np.float64), **arr)
    print('cache saved', cache_path(k, N), len(ids), 'stocks', int(off[-1]), 'bars', flush=True)

def fetch_bars(bs, code, start, end, adj):
    rr = []
    for attempt in range(3):
        try:
            rr = rows(bs.query_history_k_data_plus(code, 'date,open,high,low,close,volume,amount,turn,tradestatus,isST', start_date=start, end_date=end, frequency='d', adjustflag=adj))
            break
        except Exception:
            time.sleep(2)
            try:
                bs.login()
            except Exception:
                pass
    bars = []
    for r in rr:
        try:
            if r[8] != '1' or float(r[5] or 0) <= 0:
                continue
            bars.append((int(r[0].replace('-', '')), float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]) / 100.0, float(r[6] or 0), float(r[7] or 0), r[9] == '1'))
        except ValueError:
            continue
    if not bars:
        return None
    a = np.array([b[:8] for b in bars], dtype=float)
    out = {f: a[:, q] for q, f in enumerate(FIELDS[:8])}
    out['d'] = out['d'].astype(np.int64)
    out['st'] = np.array([b[8] for b in bars])
    return out

def real_close(bs, code, end):
    """(date, unadjusted close) of the last bar up to `end`."""
    b = fetch_bars(bs, code, str(datetime.date.fromisoformat(end) - datetime.timedelta(days=20)), end, '3')
    return (int(b['d'][-1]), float(b['c'][-1])) if b else (0, float('nan'))

def merge(old, new):
    if old is None:
        return new
    if new is None:
        return old
    keep = old['d'] < new['d'][0]
    return {f: np.concatenate([old[f][keep], new[f]]) for f in FIELDS}

def shard(k, N, mode):
    """mode: backfill = download everything from BaoStock; daily = cached history + the new days;
    recompute = cached history only (after a rule change), BaoStock only for the calendar, industries and indices."""
    import baostock as bs
    lg = bs.login()
    if lg.error_code != '0':
        sys.exit('login failed ' + lg.error_msg)
    today = bjtoday()
    td = [r[0] for r in rows(bs.query_trade_dates(start_date=str(today - datetime.timedelta(days=30)), end_date=str(today))) if r[1] == '1']
    D = None
    for d in reversed(td):
        lst = rows(bs.query_all_stock(day=d))
        if len(lst) > 1000:
            D = d
            break
    Dn = int(D.replace('-', ''))
    ok_code = lambda c: c[:5] in ('sh.60', 'sh.68', 'sz.00', 'sz.30')
    listed = {r[0]: r[2] for r in lst if ok_code(r[0])}
    cache = None if mode == 'backfill' else load_cache(k, N)
    if cache is None and mode != 'backfill':
        print('no cache for this shard: downloading everything', flush=True)
    full = mode in ('backfill', 'recompute') or cache is None
    if cache is None:
        basic = rows(bs.query_stock_basic())
        codes = sorted((r[0], r[1]) for r in basic if r[4] == '1' and ok_code(r[0]))
    else:
        known = {('sh.' if s[0] == '1' else 'sz.') + s[2:] for s in cache}
        codes = sorted(set(listed) | known)
        codes = [(c, listed.get(c, '')) for c in codes]
    codes = [c for c in codes if (zlib.crc32(c[0].encode()) % N) == k]
    print('shard', k, 'of', N, mode, 'date', D, 'stocks', len(codes), 'cached', 0 if cache is None else len(cache), flush=True)
    # 证监会行业 (latest), used to tell 科技 from the rest
    inds = {}
    try:
        for r in rows(bs.query_stock_industry()):
            inds[r[1]] = (r[3] or '')[:3]
    except Exception as e:
        print('industry failed', e, flush=True)
    # gate indices: 科创50 (from 2019-12), 创业板指, 上证
    ix_start = BACKFILL_START if full else str(datetime.date.fromisoformat(D) - datetime.timedelta(days=1300))
    ix = {}
    for key, icode in INDEX.items():
        rr = rows(bs.query_history_k_data_plus(icode, 'date,close', start_date=ix_start, end_date=D, frequency='d'))
        ix[key] = {int(r[0].replace('-', '')): float(r[1]) for r in rr if r[1]}
    def gate_series(dd, tech):
        if not tech:
            return np.array([ix['sh'].get(int(x), np.nan) for x in dd])
        return np.array([ix['kc'].get(int(x), ix['cy'].get(int(x), np.nan)) for x in dd])
    keep = {key: [] for key in ('d', 'id', 'amt', 'turn', 'flags', 'fails', 'chg', 'ret20')}
    base, names, store = {}, {}, {}
    mkt = {}          # date -> [all-stock turnover, 科技 turnover, n stocks, n up]
    t0 = time.time()
    for j, (code, name) in enumerate(codes):
        if j % 200 == 0:
            print(j, round(time.time() - t0), flush=True)
        sid = ('1.' if code.startswith('sh') else '0.') + code[3:]
        old = cache.get(sid) if cache else None
        rc = old['rc'] if old else (0, float('nan'))
        if old is None:
            if mode == 'recompute' and cache is not None and code not in listed:
                continue
            raw = fetch_bars(bs, code, BACKFILL_START, D, '1')
            if raw is not None and code in listed:
                rc = real_close(bs, code, D)
        elif mode == 'daily' and code in listed and int(old['d'][-1]) < Dn:
            # the new days, starting from the last cached day so the overlap is replaced
            last = str(int(old['d'][-1]))
            new = fetch_bars(bs, code, f'{last[:4]}-{last[4:6]}-{last[6:]}', D, '1')
            raw = merge(old, new)
            rc = real_close(bs, code, D)
        else:
            raw = old
        if raw is None:
            continue
        raw = {f: raw[f] for f in FIELDS}
        store[sid] = dict(raw, rc=rc)
        if len(raw['d']) < NB:
            continue
        if not full and len(raw['d']) > 900:
            raw = {f: v[-900:] for f, v in raw.items()}
        d = raw['d'].astype(np.int64)
        O, H, L, C, V, A, T = (raw[f].astype(float) for f in ('o', 'h', 'l', 'c', 'v', 'a', 't'))
        st = raw['st'].astype(bool)
        tech = is_tech(code[3:], inds.get(code))
        flags, fails, chg, ret20, valid = evaluate(code[3:], d, O, H, L, C, V, gate_series(d, tech))
        if tech:
            flags |= np.uint16(1 << BITS.index('tech'))
        names[sid] = name or names.get(sid, '')
        m = valid & ~st & (A > 0)
        for x, a_, c_ in zip(d[~st & (A > 0)], A[~st & (A > 0)], chg[~st & (A > 0)]):
            t = mkt.setdefault(int(x), [0.0, 0.0, 0, 0])
            t[0] += a_
            if tech:
                t[1] += a_
            t[2] += 1
            if c_ > 0:
                t[3] += 1
        for key, arr in (('d', d), ('amt', A), ('turn', T), ('flags', flags), ('fails', fails), ('chg', chg), ('ret20', ret20)):
            keep[key].append(arr[m])
        keep['id'].append(np.full(m.sum(), sid, dtype='U9'))
        # base record for the page's live check (only stocks that traded on the last day), in 前复权 (today's real) prices
        if int(d[-1]) == Dn and rc[0] == Dn and np.isfinite(rc[1]) and C[-1] > 0:
            fx = rc[1] / C[-1]
            last = slice(len(d) - NB, len(d))
            cents = np.round(C[last] * fx * 100).astype(int)
            v5 = roll(V, 5, np.mean)
            base[sid] = {
                'c': [int(cents[0])] + [int(x) for x in np.diff(cents)],
                'h': [int(x) for x in np.round(H[last] * fx * 100).astype(int) - cents],
                'o': [int(x) for x in (np.round(O[-NO:] * fx * 100).astype(int) - cents[-NO:])],
                'v': [int(round(x)) for x in V[-NV:]],
                'l': int(round(L[-1] * fx * 100)),
                'm': int(round(np.nanmax(v5[-500:]))),
                'n': name,
                'd': [f'{x // 10000:04d}-{x // 100 % 100:02d}-{x % 100:02d}' for x in d[last]],
                'a20': float(A[-20:].mean()),
                'g': inds.get(code, ''),
            }
    bs.logout()
    if mode != 'recompute' or cache is None:
        save_cache(k, N, store)
    os.makedirs('out', exist_ok=True)
    cat = {key: (np.concatenate(v) if v else np.array([])) for key, v in keep.items()}
    # only rows that can make a day's top 200: the shard's own top 200 per day
    if len(cat['d']):
        order = np.lexsort((-cat['amt'], cat['d']))
        dd = cat['d'][order]
        first = np.r_[0, np.flatnonzero(np.diff(dd)) + 1]
        rank = np.arange(len(dd)) - np.repeat(first, np.diff(np.r_[first, len(dd)]))
        sel = order[rank < POOL]
        cat = {key: v[sel] for key, v in cat.items()}
    np.savez_compressed(f'out/shard_{k}.npz', **cat)
    with open(f'out/base_{k}.json', 'w', encoding='utf-8') as fh:
        json.dump({'D': D, 'base': base, 'names': names, 'inds': {('1.' if c.startswith('sh') else '0.') + c[3:]: v for c, v in inds.items()},
                   'ix': {key: {str(x): v for x, v in ser.items()} for key, ser in ix.items()}, 'mkt': {str(x): v for x, v in mkt.items()}}, fh, ensure_ascii=False)
    print('shard done', k, len(cat['d']), 'rows', round(time.time() - t0), 's', flush=True)

def combine(mode):
    shards = sorted(glob.glob('out/shard_*.npz'))
    if not shards:
        sys.exit('no shards')
    parts = [np.load(p) for p in shards]
    cat = {key: np.concatenate([p[key] for p in parts]) for key in parts[0].files}
    bases, names, inds, ix, mkt, D = {}, {}, {}, {}, {}, None
    for p in sorted(glob.glob('out/base_*.json')):
        j = json.load(open(p, encoding='utf-8'))
        bases.update(j['base']); names.update(j['names']); D = j['D'] or D
        inds.update(j.get('inds', {}))
        for key, ser in j.get('ix', {}).items():
            if len(ser) > len(ix.get(key, {})):
                ix[key] = ser
        for x, v in j.get('mkt', {}).items():
            t = mkt.setdefault(int(x), [0.0, 0.0, 0, 0])
            for q in range(4):
                t[q] += v[q]
    # gate index levels per day: 2 = 收盘在 10 日线上（共振），1 = 只在 5 日线上，0 = 5 日线下
    gates = {}
    for key, ser in ix.items():
        ds = sorted(int(x) for x in ser)
        cl = np.array([ser[str(x)] for x in ds])
        m5, m10 = roll(cl, 5, np.mean), roll(cl, 10, np.mean)
        for q, x in enumerate(ds):
            if q >= 9:
                lv = 2 if cl[q] >= m10[q] else 1 if cl[q] >= m5[q] else 0
                gates.setdefault(x, {})[key] = [lv, round((cl[q] / cl[q - 1] - 1) * 100, 2)]
    bit = {k: 1 << b for b, k in enumerate(BITS)}
    # per day: global top 200 by turnover, rank by 20-day return inside that pool
    order = np.lexsort((-cat['amt'], cat['d']))
    for key in cat:
        cat[key] = cat[key][order]
    dd = cat['d']
    bounds = np.r_[0, np.flatnonzero(np.diff(dd)) + 1, len(dd)]
    days = {}
    full = mode in ('backfill', 'recompute')
    limit_from = int(PICKS_FROM.replace('-', '')) if full else 0
    for s, e in zip(bounds[:-1], bounds[1:]):
        day = int(dd[s])
        if day < limit_from:
            continue
        e2 = min(e, s + POOL)
        idx = np.arange(s, e2)
        r20 = cat['ret20'][idx]
        rk = np.empty(len(idx), dtype=int)
        rk[np.argsort(-np.nan_to_num(r20, nan=-9e9), kind='stable')] = np.arange(1, len(idx) + 1)
        ids = [str(x) for x in cat['id'][idx]]
        st = state_of(cat['flags'][idx].astype(np.int64))
        # 主线：成交额前 40 里，同一个行业有 3 只以上，并且是数量最多的两个行业之一
        cnt = {}
        for sid in ids[:40]:
            g = inds.get(sid, '')
            if g:
                cnt[g] = cnt.get(g, 0) + 1
        top2 = sorted(cnt.items(), key=lambda kv: -kv[1])[:3]
        main = {g for g, c in top2[:2] if c >= 3}
        # 大马拉小车：成交额前 100 里，换手率排在前三成
        t100 = np.array([float(x) for x in cat['turn'][idx][:100]])
        dm_cut = np.nanpercentile(t100, 70) if len(t100) >= 10 else np.inf
        recs = []
        for q, i in enumerate(idx):
            fl = int(cat['fails'][i])
            if st[q] == 0 and fl > 1:
                continue
            x = (1 if q < 100 and float(cat['turn'][i]) >= dm_cut else 0) | (2 if inds.get(ids[q], '') in main else 0)
            recs.append([ids[q], round(float(cat['chg'][i]) * 100, 2), round(float(cat['amt'][i]) / 1e8, 2), round(float(cat['turn'][i]), 2), int(cat['flags'][i]), int(rk[q]), fl, x])
        key = f'{day // 10000:04d}-{day // 100 % 100:02d}-{day % 100:02d}'
        mk = mkt.get(day)
        c100 = cat['chg'][idx][:100]
        days[key] = {'n': len(idx), 'r': recs, 'g': gates.get(day, {}), 'ms': [[g, c] for g, c in top2],
                     'm': ({'amt': round(mk[0] / 1e8), 'ts': round(mk[1] / mk[0] * 100, 1) if mk[0] else 0, 'up': int(mk[3]), 'cnt': int(mk[2]), 'u100': int(np.sum(c100 > 0))} if mk else {})}
    keys = sorted(days)
    if not full:
        keys = keys[-30:]          # the daily run refreshes the last 30 trading days
    os.makedirs('picks', exist_ok=True)
    years = sorted({k[:4] for k in keys})
    for y in years:
        path = f'picks/{y}.json'
        cur = json.load(open(path, encoding='utf-8')) if os.path.exists(path) and not full else {}
        for k in keys:
            if k[:4] == y:
                cur[k] = days[k]
        with open(path, 'w', encoding='utf-8') as fh:
            json.dump(dict(sorted(cur.items())), fh, ensure_ascii=False, separators=(',', ':'))
    idxp = 'picks/index.json'
    allyears = sorted({os.path.basename(p)[:4] for p in glob.glob('picks/[0-9][0-9][0-9][0-9].json')})
    first = min(json.load(open(f'picks/{allyears[0]}.json', encoding='utf-8')))
    last = max(json.load(open(f'picks/{allyears[-1]}.json', encoding='utf-8')))
    with open(idxp, 'w') as fh:
        json.dump({'years': allyears, 'first': first, 'last': last, 'bits': BITS, 'v': 2}, fh)
    # names: keep old names for delisted stocks
    oldn = json.load(open('names.json', encoding='utf-8')) if os.path.exists('names.json') else {}
    oldn.update(names)
    with open('names.json', 'w', encoding='utf-8') as fh:
        json.dump(oldn, fh, ensure_ascii=False, separators=(',', ':'))

    # base file for the live page
    top = sorted(bases.items(), key=lambda kv: -kv[1]['a20'])[:KEEP]
    cal = max({tuple(v['d']) for _, v in top}, key=lambda c: sum(1 for _, v in top if tuple(v['d']) == c))
    out = {'d': D, 'dates': list(cal), 'made': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'), 's': {}, 'v': 2,
           'ix': {key: [ser.get(x.replace('-', '')) for x in cal] for key, ser in ix.items()}}
    for sid, v in top:
        v = dict(v); del v['a20']
        if tuple(v['d']) == cal:
            del v['d']
        out['s'][sid] = v
    with open('base.json', 'w', encoding='utf-8') as fh:
        json.dump(out, fh, ensure_ascii=False, separators=(',', ':'))
    print('combine done', len(days), 'days;', len(out['s']), 'base stocks; date', D, flush=True)

if __name__ == '__main__':
    if sys.argv[1] == 'shard':
        shard(int(sys.argv[2]), int(sys.argv[3]), sys.argv[4])
    else:
        combine(sys.argv[2])
