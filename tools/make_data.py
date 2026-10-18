# Data for the screening page, built on GitHub Actions from BaoStock (free, meant for bulk download).
#   python tools/make_data.py shard K N MODE    -> out/shard_K.npz, out/base_K.json   (MODE: daily | backfill)
#   python tools/make_data.py combine MODE      -> base.json, picks/YYYY.json, names.json, dashi.json
# 大势 history is not built here: the page reads 同花顺's yearly index files directly when a past day is picked.
# The rules match the page's own check (rt.js evalAt): every stock is checked on every trading day,
# and each day keeps the stocks that pass, or miss by one condition, among that day's top 200 by turnover.
import sys, os, json, time, datetime, glob
import numpy as np

POOL, KEEP, NB, NV, NO = 200, 1800, 61, 30, 11
BACKFILL_START = '2009-06-01'
PICKS_FROM = '2010-01-04'
BITS = ['above5', 'up5', 'bias', 'notLimDn', 'heap', 'nearHigh', 'reclaim', 'pull5', 'pull10', 'newHigh', 'extreme']

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

def evaluate(code, d, O, H, L, C, V):
    n = len(C)
    m5, m10, m20 = roll(C, 5, np.mean), roll(C, 10, np.mean), roll(C, 20, np.mean)
    m5p = shift(m5, 1)
    v5 = roll(V, 5, np.mean)
    v20 = shift(roll(V, 20, np.mean), 5)
    hi60 = roll(H, 60, np.max)
    c60p = shift(roll(C, 60, np.max), 1)
    pc = shift(C, 1)
    with np.errstate(invalid='ignore', divide='ignore'):
        chg = C / pc - 1
        lim = np.where(np.char.startswith(np.full(n, code), '688'), 0.2,
              np.where(np.char.startswith(np.full(n, code), '30') & (d >= 20200824), 0.2, 0.1))
        limdn = chg <= -(lim - 0.002)
        f = {}
        f['above5'] = C > m5
        f['up5'] = m5 > m5p
        f['bias'] = C / m5 - 1 <= 0.08
        f['notLimDn'] = ~limdn
        f['heap'] = v5 >= 1.2 * v20
        up = (m5 > m10) & (m10 > m20) & (m20 > shift(m20, 5))
        f['reclaim'] = (pc <= m5p) & (C > m5) & (V >= 1.5 * shift(V, 1))
        p5 = (L <= m5 * 1.01) & (C >= m5)
        f['pull5'] = up & p5
        f['pull10'] = up & (L <= m10 * 1.01) & (C >= m10) & ~p5
        f['newHigh'] = C > c60p
        f['nearHigh'] = C >= 0.97 * hi60
        vmax = roll(np.nan_to_num(v5, nan=0.0), 500, np.max)
        vmax[:499] = np.maximum.accumulate(np.nan_to_num(v5, nan=0.0))[:499]
        f['extreme'] = (v5 >= vmax * 0.999) & (np.arange(n) >= 120)
        must = ['above5', 'up5', 'bias', 'notLimDn', 'heap']
        fails = sum((~f[k]).astype(np.int8) for k in must) + (~(f['nearHigh'] | f['reclaim'] | f['pull5'] | f['pull10'] | f['newHigh'])).astype(np.int8)
        ret20 = C / shift(C, 20) - 1
    flags = np.zeros(n, dtype=np.uint16)
    for b, k in enumerate(BITS):
        flags |= (np.nan_to_num(f[k].astype(float), nan=0).astype(np.uint16) << b)
    valid = np.arange(n) >= 60
    return flags, fails.astype(np.int8), chg, ret20, valid

def shard(k, N, mode):
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
    if mode == 'backfill':
        basic = rows(bs.query_stock_basic())
        codes = sorted((r[0], r[1]) for r in basic if r[4] == '1' and r[0][:5] in ('sh.60', 'sh.68', 'sz.00', 'sz.30'))
        start = BACKFILL_START
    else:
        codes = sorted((r[0], r[2]) for r in lst if r[0][:5] in ('sh.60', 'sh.68', 'sz.00', 'sz.30'))
        start = str(datetime.date.fromisoformat(D) - datetime.timedelta(days=800))
    codes = codes[k::N]
    print('shard', k, 'of', N, mode, 'date', D, 'stocks', len(codes), flush=True)
    keep = {key: [] for key in ('d', 'id', 'amt', 'turn', 'flags', 'fails', 'chg', 'ret20')}
    base, names = {}, {}
    t0 = time.time()
    for j, (code, name) in enumerate(codes):
        if j % 200 == 0:
            print(j, round(time.time() - t0), flush=True)
        rr = []
        for attempt in range(3):
            try:
                rr = rows(bs.query_history_k_data_plus(code, 'date,open,high,low,close,volume,amount,turn,tradestatus,isST', start_date=start, end_date=D, frequency='d', adjustflag='2'))
                break
            except Exception as e:
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
        if len(bars) < 61:
            continue
        a = np.array([b[:8] for b in bars], dtype=float)
        st = np.array([b[8] for b in bars])
        d = a[:, 0].astype(np.int64)
        O, H, L, C, V, A, T = a[:, 1], a[:, 2], a[:, 3], a[:, 4], a[:, 5], a[:, 6], a[:, 7]
        flags, fails, chg, ret20, valid = evaluate(code[3:], d, O, H, L, C, V)
        sid = ('1.' if code.startswith('sh') else '0.') + code[3:]
        names[sid] = name
        m = valid & ~st & (A > 0)
        for key, arr in (('d', d), ('amt', A), ('turn', T), ('flags', flags), ('fails', fails), ('chg', chg), ('ret20', ret20)):
            keep[key].append(arr[m])
        keep['id'].append(np.full(m.sum(), sid, dtype='U9'))
        # base record for the page's live check (only stocks that traded on the last day)
        if str(d[-1]) == D.replace('-', ''):
            last = slice(len(bars) - NB, len(bars))
            cents = np.round(C[last] * 100).astype(int)
            v5 = roll(V, 5, np.mean)
            base[sid] = {
                'c': [int(cents[0])] + [int(x) for x in np.diff(cents)],
                'h': [int(x) for x in np.round(H[last] * 100).astype(int) - cents],
                'o': [int(x) for x in (np.round(O[-NO:] * 100).astype(int) - cents[-NO:])],
                'v': [int(round(x)) for x in V[-NV:]],
                'l': int(round(L[-1] * 100)),
                'm': int(round(np.nanmax(v5[-500:]))),
                'n': name,
                'd': [f'{x // 10000:04d}-{x // 100 % 100:02d}-{x % 100:02d}' for x in d[last]],
                'a20': float(A[-20:].mean()),
            }
    bs.logout()
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
        json.dump({'D': D, 'base': base, 'names': names}, fh, ensure_ascii=False)
    print('shard done', k, len(cat['d']), 'rows', round(time.time() - t0), 's', flush=True)

def combine(mode):
    shards = sorted(glob.glob('out/shard_*.npz'))
    if not shards:
        sys.exit('no shards')
    parts = [np.load(p) for p in shards]
    cat = {key: np.concatenate([p[key] for p in parts]) for key in parts[0].files}
    bases, names, D = {}, {}, None
    for p in sorted(glob.glob('out/base_*.json')):
        j = json.load(open(p, encoding='utf-8'))
        bases.update(j['base']); names.update(j['names']); D = j['D'] or D
    # per day: global top 200 by turnover, rank by 20-day return inside that pool
    order = np.lexsort((-cat['amt'], cat['d']))
    for key in cat:
        cat[key] = cat[key][order]
    dd = cat['d']
    bounds = np.r_[0, np.flatnonzero(np.diff(dd)) + 1, len(dd)]
    days = {}
    limit_from = int(PICKS_FROM.replace('-', '')) if mode == 'backfill' else 0
    for s, e in zip(bounds[:-1], bounds[1:]):
        day = int(dd[s])
        if day < limit_from:
            continue
        e2 = min(e, s + POOL)
        idx = np.arange(s, e2)
        r20 = cat['ret20'][idx]
        rk = np.empty(len(idx), dtype=int)
        rk[np.argsort(-np.nan_to_num(r20, nan=-9e9), kind='stable')] = np.arange(1, len(idx) + 1)
        recs = []
        for q, i in enumerate(idx):
            fl = int(cat['fails'][i])
            if fl > 1:
                continue
            recs.append([str(cat['id'][i]), round(float(cat['chg'][i]) * 100, 2), round(float(cat['amt'][i]) / 1e8, 2), round(float(cat['turn'][i]), 2), int(cat['flags'][i]), int(rk[q]), fl])
        key = f'{day // 10000:04d}-{day // 100 % 100:02d}-{day % 100:02d}'
        days[key] = {'n': len(idx), 'r': recs}
    keys = sorted(days)
    if mode != 'backfill':
        keys = keys[-30:]          # the daily run refreshes the last 30 trading days
    os.makedirs('picks', exist_ok=True)
    years = sorted({k[:4] for k in keys})
    for y in years:
        path = f'picks/{y}.json'
        cur = json.load(open(path, encoding='utf-8')) if os.path.exists(path) and mode != 'backfill' else {}
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
        json.dump({'years': allyears, 'first': first, 'last': last, 'bits': BITS}, fh)
    # names: keep old names for delisted stocks
    oldn = json.load(open('names.json', encoding='utf-8')) if os.path.exists('names.json') else {}
    oldn.update(names)
    with open('names.json', 'w', encoding='utf-8') as fh:
        json.dump(oldn, fh, ensure_ascii=False, separators=(',', ':'))
    # base file for the live page
    top = sorted(bases.items(), key=lambda kv: -kv[1]['a20'])[:KEEP]
    cal = max({tuple(v['d']) for _, v in top}, key=lambda c: sum(1 for _, v in top if tuple(v['d']) == c))
    out = {'d': D, 'dates': list(cal), 'made': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'), 's': {}}
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
