'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import PageHeader from '@/app/components/PageHeader'
import BossAnalysis from '@/app/boss/analysis/AnalysisContent'
import ZhilianAnalysis from '@/app/zhilian/analysis/AnalysisContent'
import LiepinAnalysis from '@/app/liepin/analysis/AnalysisContent'
import Job51Analysis from '@/app/51job/analysis/AnalysisContent'
import { API_BASE } from '@/lib/api'
import { BiBriefcase, BiRefresh, BiDownload } from 'react-icons/bi'

/**
 * 全平台投递记录。
 *
 * 原来这一页复用 Boss 的 AnalysisContent，取数写死 /api/boss/list，只能看一家。
 * 这里改成并行拉五个平台各自已有的接口，在前端统一字段后合并展示：
 *   Boss / 智联 / 猎聘 / 51job → /api/{platform}/list
 *   实习僧                    → /api/shixiseng/jobs（返回 {data:[...]}，形状不同）
 *
 * 为什么不加后端聚合接口：Spring 的静态资源配置把
 * file:src/main/resources/dist/ 放在第一位且禁用缓存，前端重新 build 即生效，
 * 不需要重启桥接 —— 投递任务跑着的时候这点很重要。
 *
 * 各平台字段名不一致（company_name / comp_name、job_name / job_title、
 * delivery_status / delivered 0-1…），所以用 pick() 按候选键容错取值，
 * 以后某个平台加字段也不会整页崩。
 */

type Row = {
  platform: string
  company: string
  job: string
  salary: string
  city: string
  status: string
  time: string
  url: string
}

type Source = {
  key: string
  label: string
  color: string
  path: string
  items: (j: any) => any[]
}

const SOURCES: Source[] = [
  { key: 'boss', label: 'Boss直聘', color: '#4f46e5', path: '/api/boss/list', items: j => j?.items ?? [] },
  { key: 'zhilian', label: '智联招聘', color: '#0891b2', path: '/api/zhilian/list', items: j => j?.items ?? [] },
  { key: 'liepin', label: '猎聘', color: '#7c3aed', path: '/api/liepin/list', items: j => j?.items ?? [] },
  { key: '51job', label: '51job', color: '#2563eb', path: '/api/51job/list', items: j => j?.items ?? [] },
  { key: 'shixiseng', label: '实习僧', color: '#059669', path: '/api/shixiseng/jobs', items: j => j?.data ?? [] },
]

const S = (v: any) => (v == null ? '' : String(v))
const pick = (o: any, keys: string[]) => {
  for (const k of keys) {
    const v = o?.[k]
    if (v != null && v !== '' && v !== 0) return S(v)
  }
  return ''
}

function normalize(item: any, src: Source): Row {
  let status = pick(item, ['deliveryStatus', 'delivery_status', 'status'])
  if (!status && (item?.delivered === 1 || item?.delivered === true)) status = '已投递'
  if (!status && (item?.delivered === 0 || item?.delivered === false)) status = '未投递'
  // 后端返回的是 Java 实体的驼峰字段，数据库里的下划线写法也留着兜底
  const t = pick(item, ['updatedAt', 'updateTime', 'updated_at', 'update_time',
    'createdAt', 'createTime', 'created_at', 'create_time', 'publishTime', 'refreshTime'])
  return {
    platform: src.label,
    company: pick(item, ['companyName', 'compName', 'company_name', 'comp_name', 'company']),
    job: pick(item, ['jobName', 'jobTitle', 'job_name', 'job_title', 'name']),
    salary: pick(item, ['salary', 'jobSalaryText', 'job_salary_text', 'salaryText']),
    city: pick(item, ['location', 'jobArea', 'city', 'job_area', 'area']),
    status: status || '—',
    time: t.slice(0, 19).replace('T', ' '),
    url: pick(item, ['jobUrl', 'jobLink', 'job_url', 'job_link', 'url']),
  }
}

function statusClass(s: string) {
  if (s === '已投递') return 'ok'
  if (s === '已过滤' || s === '岗位已下线' || s === '投递失败') return 'bad'
  if (s === '待人工定制' || s === '投递待复核') return 'mid'
  return 'wait'
}

/** 合并视图：五个平台拉成一张表，带平台/状态筛选、搜索、分页、CSV 导出。 */
function MergedView() {
  const [rows, setRows] = useState<Row[]>([])
  const [loading, setLoading] = useState(true)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [updated, setUpdated] = useState('')
  const [platform, setPlatform] = useState('')
  const [status, setStatus] = useState('')
  const [q, setQ] = useState('')
  const [page, setPage] = useState(1)
  const PAGE_SIZE = 50

  const load = useCallback(async () => {
    setLoading(true)
    const errs: Record<string, string> = {}
    const results = await Promise.all(SOURCES.map(async src => {
      try {
        // 实习僧 /jobs 每页硬上限 200，size=1000 也只能拿 200，所以要翻页；
        // 其余平台一页就够，循环会因为 all.length >= total 立刻退出。
        const all: any[] = []
        let total = Infinity
        for (let p = 1; p <= 6; p++) {
          const sep = src.path.includes('?') ? '&' : '?'
          const res = await fetch(`${API_BASE}${src.path}${sep}page=${p}&size=1000`, { cache: 'no-store' })
          if (!res.ok) throw new Error(`HTTP ${res.status}`)
          const j = await res.json()
          const arr = src.items(j)
          if (!Array.isArray(arr)) throw new Error('返回结构不是数组')
          if (typeof j?.total === 'number') total = j.total
          else if (typeof j?.stats?.total === 'number') total = j.stats.total
          all.push(...arr)
          if (arr.length === 0 || all.length >= total) break
        }
        return all.map(x => normalize(x, src))
      } catch (e: any) {
        errs[src.label] = e?.message || '加载失败'
        return [] as Row[]
      }
    }))
    setRows(results.flat())
    setErrors(errs)
    setUpdated(new Date().toLocaleTimeString('zh-CN', { hour12: false }))
    setLoading(false)
  }, [])

  useEffect(() => { load() }, [load])

  const per = useMemo(() => SOURCES.map(s => {
    const sub = rows.filter(r => r.platform === s.label)
    return { ...s, total: sub.length, delivered: sub.filter(r => r.status === '已投递').length }
  }), [rows])

  const delivered = useMemo(() => rows.filter(r => r.status === '已投递').length, [rows])

  const filtered = useMemo(() => {
    const kw = q.trim().toLowerCase()
    return rows.filter(r =>
      (!platform || r.platform === platform) &&
      (!status || r.status === status) &&
      (!kw || `${r.company} ${r.job} ${r.city}`.toLowerCase().includes(kw))
    ).sort((a, b) => b.time.localeCompare(a.time))
  }, [rows, platform, status, q])

  const pages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const cur = Math.min(page, pages)
  const shown = filtered.slice((cur - 1) * PAGE_SIZE, cur * PAGE_SIZE)

  const exportCsv = () => {
    const head = ['平台', '公司', '岗位', '薪资', '城市', '状态', '时间', '链接']
    const cell = (v: string) => `"${String(v || '').replace(/"/g, '""')}"`
    const csv = '\ufeff' + [head.join(','), ...filtered.map(r =>
      [r.platform, r.company, r.job, r.salary, r.city, r.status, r.time, r.url].map(cell).join(',')
    )].join('\r\n')
    const a = document.createElement('a')
    a.href = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    a.download = 'delivery_all_platforms.csv'
    a.click()
    URL.revokeObjectURL(a.href)
  }

  return (
    <div style={{ display: 'grid', gap: 18 }}>
      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
        <button className="btn" onClick={load} disabled={loading}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <BiRefresh /> {loading ? '加载中…' : '刷新'}
        </button>
        <button className="btn" onClick={exportCsv}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <BiDownload /> 导出 CSV
        </button>
        <span style={{ fontSize: 12, color: '#6b7280' }}>
          最近刷新 {updated || '—'}{Object.keys(errors).length ? ` · ${Object.keys(errors).length} 个平台读取失败` : ''}
        </span>
      </div>

      {Object.keys(errors).length > 0 && (
        <div style={{ background: '#fef2f2', border: '1px solid #fecaca', color: '#991b1b',
          borderRadius: 10, padding: '10px 14px', fontSize: 12.5 }}>
          读取失败：{Object.entries(errors).map(([k, v]) => `${k}（${v}）`).join('、')} · 其余平台正常显示
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(160px,1fr))', gap: 12 }}>
        {[
          { n: rows.length, l: '岗位记录总数' },
          { n: delivered, l: '已投递（真正发出）' },
          { n: rows.length - delivered, l: '未发出 / 待处理' },
          { n: SOURCES.length, l: '平台' },
        ].map((k, i) => (
          <div key={i} style={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 12, padding: '14px 16px' }}>
            <div style={{ fontSize: 26, fontWeight: 700, color: i === 1 ? '#4f46e5' : '#111827' }}>{k.n}</div>
            <div style={{ fontSize: 12, color: '#6b7280', marginTop: 3 }}>{k.l}</div>
          </div>
        ))}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(180px,1fr))', gap: 12 }}>
        {per.map(p => (
          <div key={p.key} style={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 12, padding: '12px 15px' }}>
            <div style={{ fontSize: 13, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 7 }}>
              <i style={{ width: 9, height: 9, borderRadius: '50%', background: p.color, display: 'inline-block' }} />
              {p.label}
            </div>
            <div style={{ fontSize: 21, fontWeight: 700, margin: '7px 0 2px' }}>
              {p.total}<span style={{ fontSize: 12, fontWeight: 400, color: '#9ca3af' }}> 条</span>
            </div>
            <div style={{ fontSize: 12, color: '#6b7280' }}>已投递 <b>{p.delivered}</b></div>
            <div style={{ height: 5, background: '#eef2f7', borderRadius: 3, marginTop: 8, overflow: 'hidden' }}>
              <i style={{ display: 'block', height: '100%', borderRadius: 3, background: p.color,
                width: `${p.total ? (100 * p.delivered / p.total) : 0}%` }} />
            </div>
          </div>
        ))}
      </div>

      <div style={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 12,
        padding: '12px 14px', display: 'flex', gap: 9, flexWrap: 'wrap', alignItems: 'center' }}>
        <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
          {['全部', ...SOURCES.map(s => s.label)].map(n => (
            <button key={n} className="btn"
              onClick={() => { setPlatform(n === '全部' ? '' : n); setPage(1) }}
              style={{ background: (platform || '全部') === n ? '#4f46e5' : '#fff',
                color: (platform || '全部') === n ? '#fff' : '#374151',
                borderColor: (platform || '全部') === n ? '#4f46e5' : '#e5e7eb' }}>
              {n}
            </button>
          ))}
        </div>
        <select value={status} onChange={e => { setStatus(e.target.value); setPage(1) }} className="inp">
          <option value="">全部状态</option>
          {['已投递', '未投递', '已过滤', '待人工定制', '投递待复核', '岗位已下线'].map(s =>
            <option key={s}>{s}</option>)}
        </select>
        <input className="inp" type="search" placeholder="搜公司 / 岗位 / 城市" value={q}
          onChange={e => { setQ(e.target.value); setPage(1) }} style={{ minWidth: 200 }} />
        <span style={{ flex: 1 }} />
        <span style={{ fontSize: 12, color: '#9ca3af' }}>筛出 {filtered.length} 条</span>
      </div>

      <div style={{ background: '#fff', border: '1px solid #e5e7eb', borderRadius: 12, overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
          <thead>
            <tr style={{ background: '#f9fafb' }}>
              {['平台', '公司', '岗位', '薪资', '城市', '状态', '时间', '链接'].map(h => (
                <th key={h} style={{ textAlign: 'left', padding: '10px 12px', color: '#4b5563',
                  borderBottom: '1px solid #e5e7eb', whiteSpace: 'nowrap' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {shown.map((r, i) => (
              <tr key={i} style={{ borderBottom: '1px solid #f3f4f6' }}>
                <td style={{ padding: '9px 12px', fontWeight: 600, whiteSpace: 'nowrap' }}>{r.platform}</td>
                <td style={{ padding: '9px 12px', maxWidth: 190, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.company}</td>
                <td style={{ padding: '9px 12px', maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.job}</td>
                <td style={{ padding: '9px 12px', whiteSpace: 'nowrap' }}>{r.salary}</td>
                <td style={{ padding: '9px 12px', whiteSpace: 'nowrap' }}>{r.city}</td>
                <td style={{ padding: '9px 12px' }}>
                  <span className={`pill ${statusClass(r.status)}`}>{r.status}</span>
                </td>
                <td style={{ padding: '9px 12px', whiteSpace: 'nowrap', color: '#6b7280' }}>{r.time}</td>
                <td style={{ padding: '9px 12px' }}>
                  {r.url ? <a href={r.url} target="_blank" rel="noreferrer"
                    style={{ color: '#4f46e5', textDecoration: 'none' }}>打开</a> : ''}
                </td>
              </tr>
            ))}
            {!shown.length && (
              <tr><td colSpan={8} style={{ textAlign: 'center', padding: 36, color: '#9ca3af' }}>
                {loading ? '正在读取五个平台…' : '没有匹配的记录'}
              </td></tr>
            )}
          </tbody>
        </table>
      </div>

      <div style={{ display: 'flex', gap: 8, justifyContent: 'center', alignItems: 'center', fontSize: 12, color: '#6b7280' }}>
        <button className="btn" onClick={() => setPage(Math.max(1, cur - 1))} disabled={cur <= 1}>上一页</button>
        <span>第 {cur} / {pages} 页</span>
        <button className="btn" onClick={() => setPage(cur + 1)} disabled={cur >= pages}>下一页</button>
      </div>

      <div style={{ fontSize: 11.5, color: '#9ca3af', lineHeight: 1.9 }}>
        口径：Boss / 智联 / 实习僧用 <code>delivery_status</code>；猎聘 / 51job 用 <code>delivered</code>（1 → 已投递）。<br />
        「已投递」只统计真正发出去的；未投递 / 已过滤 / 待人工定制 是各平台扫到但未发出的岗位，一并列出便于核对。<br />
        每次刷新都会重新请求五个平台接口；某个平台接口异常时只提示该平台，不影响其余数据。
      </div>

      <style>{`
        .btn { font: inherit; font-size: 12px; cursor: pointer; border: 1px solid #e5e7eb;
          background: #fff; color: #374151; border-radius: 8px; padding: 6px 12px; }
        .btn:hover:not(:disabled) { border-color: #a5b4fc; }
        .btn:disabled { opacity: .5; cursor: default; }
        .inp { font: inherit; font-size: 12px; padding: 6px 10px; border: 1px solid #e5e7eb;
          border-radius: 8px; background: #fff; color: #374151; outline: none; }
        .inp:focus { border-color: #818cf8; }
        .pill { display: inline-block; padding: 2px 8px; border-radius: 20px; font-size: 11px; font-weight: 600; white-space: nowrap; }
        .pill.ok { background:#dcfce7; color:#166534; }
        .pill.wait { background:#f3f4f6; color:#6b7280; }
        .pill.bad { background:#fee2e2; color:#991b1b; }
        .pill.mid { background:#fef3c7; color:#92400e; }
      `}</style>
    </div>
  )
}

/**
 * 全平台投递记录容器页。
 *
 * 「合并视图」是五平台一张表；其余 tab 直接挂各平台**现成的** AnalysisContent，
 * 图表、多维筛选、服务端分页、reload、导出等原有能力一个不少。
 * 原来的 /boss/records 完全不动，这里是新增入口。
 */
const TABS = ['合并视图', 'Boss直聘', '智联招聘', '猎聘', '51job']

export default function AllPlatformRecordsPage() {
  const [tab, setTab] = useState('合并视图')
  return (
    <div style={{ display: 'grid', gap: 18 }}>
      <PageHeader
        icon={<BiBriefcase style={{ fontSize: 24 }} />}
        title="全平台投递记录"
        subtitle="合并视图 + 各平台完整投递分析；Boss 原投递记录仍在侧栏「投递记录」"
        iconClass="text-white"
        accentBgClass="bg-indigo-500"
      />
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
        {TABS.map(t => (
          <button key={t} className="btn" onClick={() => setTab(t)}
            style={{ background: tab === t ? '#4f46e5' : '#fff',
              color: tab === t ? '#fff' : '#374151',
              borderColor: tab === t ? '#4f46e5' : '#e5e7eb' }}>
            {t}
          </button>
        ))}
      </div>
      {tab === '合并视图' && <MergedView />}
      {tab === 'Boss直聘' && <BossAnalysis showHeader={false} />}
      {tab === '智联招聘' && <ZhilianAnalysis showHeader={false} />}
      {tab === '猎聘' && <LiepinAnalysis showHeader={false} />}
      {tab === '51job' && <Job51Analysis showHeader={false} />}
      <div style={{ fontSize: 11.5, color: '#9ca3af', lineHeight: 1.8 }}>
        实习僧没有独立的分析页，请在「合并视图」里用平台筛选查看。<br />
        各平台 tab 走的是项目自带的投递分析组件，功能与对应平台原分析页完全一致。
      </div>
    </div>
  )
}
