'use client'

import { useCallback, useEffect, useState } from 'react'
import { BiBookOpen, BiPlay, BiRefresh, BiSearch } from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import PageHeader from '@/app/components/PageHeader'
import { apiUrl } from '@/lib/api'

interface ShixisengJob {
  id: number
  jobId: string
  jobTitle: string
  companyName: string
  salary: string
  city: string
  degree: string
  industry: string
  tags: string
  jobLink: string
  deliveryStatus: string
  createTime: string
}

interface Stats {
  total: number
  delivered: number
  pending: number
  manual: number
}

const STATUS_OPTIONS = ['', '未投递', '待人工定制', '已投递']

function statusClass(s: string) {
  const base = 'px-2 py-1 rounded-full text-xs font-medium whitespace-nowrap'
  if (s.includes('已投递')) return `${base} bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300`
  if (s.includes('待人工定制')) return `${base} bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200`
  if (s.includes('已过滤')) return `${base} bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-300`
  return `${base} bg-slate-100 text-slate-700 dark:bg-slate-800/50 dark:text-slate-300`
}

export default function ShixisengPage() {
  const [jobs, setJobs] = useState<ShixisengJob[]>([])
  const [stats, setStats] = useState<Stats>({ total: 0, delivered: 0, pending: 0, manual: 0 })
  const [loading, setLoading] = useState(false)

  // 采集参数
  const [keywords, setKeywords] = useState('Python,后端开发,测试开发,算法,大模型,数据开发')
  const [city, setCity] = useState('全国')
  const [pages, setPages] = useState(2)
  const [collecting, setCollecting] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  // 筛选
  const [filterKeyword, setFilterKeyword] = useState('')
  const [filterStatus, setFilterStatus] = useState('')

  const loadJobs = useCallback(async () => {
    setLoading(true)
    try {
      // 一次拉满（后端上限 200）。实习僧岗位池目前几百条量级，暂不做分页控件。
      const params = new URLSearchParams({ page: '1', size: '200' })
      if (filterKeyword.trim()) params.set('keyword', filterKeyword.trim())
      if (filterStatus) params.set('status', filterStatus)
      const res = await fetch(apiUrl(`/api/shixiseng/jobs?${params.toString()}`))
      const data = await res.json()
      if (data?.success) {
        setJobs(data.data || [])
        if (data.stats) setStats(data.stats)
      }
    } catch (e) {
      console.error('加载实习僧岗位失败', e)
    } finally {
      setLoading(false)
    }
  }, [filterKeyword, filterStatus])

  useEffect(() => {
    loadJobs()
  }, [loadJobs])

  const startCollect = async () => {
    setCollecting(true)
    setMessage('正在采集，请稍候（每页约 5-10 秒）…')
    try {
      const res = await fetch(apiUrl('/api/shixiseng/collect'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          keywords: keywords.split(/[,，\s]+/).filter(Boolean),
          city,
          pages,
        }),
      })
      const data = await res.json()
      setMessage(data?.message || (data?.success ? '采集完成' : '采集失败'))
      await loadJobs()
    } catch (e: any) {
      setMessage(`采集失败：${e?.message || e}`)
    } finally {
      setCollecting(false)
    }
  }

  const markStatus = async (id: number, status: string) => {
    try {
      await fetch(apiUrl(`/api/shixiseng/jobs/${id}/status`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status }),
      })
      await loadJobs()
    } catch (e) {
      console.error('更新状态失败', e)
    }
  }

  return (
    <div className="p-6 space-y-6">
      <PageHeader
        icon={<BiBookOpen />}
        title="实习僧"
        subtitle="实习僧岗位采集 —— 只读采集，不自动投递，避免消耗账号信用"
      />

      <Card>
        <CardHeader>
          <CardTitle className="text-base">采集参数</CardTitle>
          <CardDescription>
            关键词用逗号分隔；城市填中文（如 南昌、上海），留空则全国。页面数据来自站点 SSR 状态树，无需登录。
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
            <div className="md:col-span-2">
              <Label>关键词</Label>
              <Input value={keywords} onChange={(e) => setKeywords(e.target.value)} placeholder="Python,后端开发" />
            </div>
            <div>
              <Label>城市</Label>
              <Input value={city} onChange={(e) => setCity(e.target.value)} placeholder="南昌" />
            </div>
            <div>
              <Label>每个关键词页数</Label>
              <Input
                type="number"
                min={1}
                max={10}
                value={pages}
                onChange={(e) => setPages(Number(e.target.value) || 1)}
              />
            </div>
          </div>
          <div className="flex items-center gap-3">
            <Button onClick={startCollect} disabled={collecting}>
              <BiPlay className="mr-1" />
              {collecting ? '采集中…' : '开始采集'}
            </Button>
            <Button variant="outline" onClick={loadJobs} disabled={loading}>
              <BiRefresh className="mr-1" />
              刷新列表
            </Button>
            {message && <span className="text-sm text-muted-foreground">{message}</span>}
          </div>
        </CardContent>
      </Card>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Card><CardContent className="pt-6"><div className="text-sm text-muted-foreground">岗位总数</div><div className="text-2xl font-semibold">{stats.total}</div></CardContent></Card>
        <Card><CardContent className="pt-6"><div className="text-sm text-muted-foreground">未处理</div><div className="text-2xl font-semibold">{stats.pending}</div></CardContent></Card>
        <Card><CardContent className="pt-6"><div className="text-sm text-muted-foreground">待人工定制</div><div className="text-2xl font-semibold">{stats.manual}</div></CardContent></Card>
        <Card><CardContent className="pt-6"><div className="text-sm text-muted-foreground">已投递</div><div className="text-2xl font-semibold">{stats.delivered}</div></CardContent></Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">岗位列表</CardTitle>
          <CardDescription>薪资取自站点明文字段（元/天）；每周天数、实习月数为字体加密字段，不做展示</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex items-center gap-2">
              <BiSearch className="text-muted-foreground" />
              <Input
                className="w-56"
                placeholder="搜索岗位或公司"
                value={filterKeyword}
                onChange={(e) => setFilterKeyword(e.target.value)}
              />
            </div>
            <select
              className="border rounded-md px-3 py-2 text-sm bg-background"
              value={filterStatus}
              onChange={(e) => setFilterStatus(e.target.value)}
            >
              {STATUS_OPTIONS.map((s) => (
                <option key={s} value={s}>{s || '全部状态'}</option>
              ))}
            </select>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left border-b">
                  <th className="px-3 py-2">岗位</th>
                  <th className="px-3 py-2">公司</th>
                  <th className="px-3 py-2">薪资</th>
                  <th className="px-3 py-2">城市</th>
                  <th className="px-3 py-2">学历</th>
                  <th className="px-3 py-2">标签</th>
                  <th className="px-3 py-2">状态</th>
                  <th className="px-3 py-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {jobs.length === 0 && (
                  <tr>
                    <td colSpan={8} className="px-3 py-10 text-center text-muted-foreground">
                      {loading ? '加载中…' : '暂无数据，先执行一次采集'}
                    </td>
                  </tr>
                )}
                {jobs.map((j) => (
                  <tr key={j.id} className="border-b last:border-0 hover:bg-muted/40">
                    <td className="px-3 py-2 max-w-[220px]">
                      <a href={j.jobLink} target="_blank" rel="noreferrer" className="text-blue-600 hover:underline">
                        {j.jobTitle || '-'}
                      </a>
                    </td>
                    <td className="px-3 py-2 max-w-[180px]">{j.companyName || '-'}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{j.salary || '-'}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{j.city || '-'}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{j.degree || '-'}</td>
                    <td className="px-3 py-2 max-w-[180px] text-xs text-muted-foreground">{j.tags || '-'}</td>
                    <td className="px-3 py-2"><span className={statusClass(j.deliveryStatus || '')}>{j.deliveryStatus || '-'}</span></td>
                    <td className="px-3 py-2 whitespace-nowrap">
                      {j.deliveryStatus === '已投递' ? (
                        <button className="text-xs text-muted-foreground hover:underline" onClick={() => markStatus(j.id, '未投递')}>撤销</button>
                      ) : (
                        <button className="text-xs text-green-600 hover:underline" onClick={() => markStatus(j.id, '已投递')}>标记已投</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
