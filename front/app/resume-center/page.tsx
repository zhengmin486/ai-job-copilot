'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  BiUpload,
  BiTrash,
  BiPlus,
  BiSave,
  BiCheck,
  BiImage,
  BiRefresh,
  BiInfoCircle,
  BiLockAlt,
} from 'react-icons/bi'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import PageHeader from '@/app/components/PageHeader'
import { API_BASE } from '@/lib/api'

const BASE = `${API_BASE}/api/boss/config`

interface ResumeMeta {
  id: number
  name: string
  fileName: string
  isDefault: boolean
}

interface ResumeRule {
  field: string
  value: string
  resumeId: number
}

interface ReviewGate {
  enabled: boolean
  salaryMin: number
  companies: string[]
  keywords: string[]
}

interface QueueItem {
  id: number
  company: string
  jobName: string
  salary: string
  jobUrl: string
  suggestedResumeId: number | null
  status: string
  createdAt: number
}

const RULE_FIELDS = ['公司', '岗位', '关键词']

function splitList(text: string): string[] {
  return text
    .split(/[,，\n]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

function formatTime(ms: number): string {
  if (!ms) return '-'
  const d = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export default function ResumeCenterPage() {
  const [resumes, setResumes] = useState<ResumeMeta[]>([])
  const [rules, setRules] = useState<ResumeRule[]>([])
  const [gate, setGate] = useState<ReviewGate>({ enabled: false, salaryMin: 0, companies: [], keywords: [] })
  const [queue, setQueue] = useState<QueueItem[]>([])
  const [companiesText, setCompaniesText] = useState('')
  const [keywordsText, setKeywordsText] = useState('')
  const [uploadName, setUploadName] = useState('')
  const [busy, setBusy] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)

  const loadResumes = useCallback(async () => {
    const res = await fetch(`${BASE}/resumes`)
    const json = await res.json()
    setResumes(Array.isArray(json.data) ? json.data : [])
  }, [])

  const loadRules = useCallback(async () => {
    const res = await fetch(`${BASE}/resume-rules`)
    const json = await res.json()
    setRules(Array.isArray(json.data) ? json.data : [])
  }, [])

  const loadGate = useCallback(async () => {
    const res = await fetch(`${BASE}/review-gate`)
    const json = await res.json()
    if (json.data) {
      const g: ReviewGate = {
        enabled: Boolean(json.data.enabled),
        salaryMin: Number(json.data.salaryMin) || 0,
        companies: Array.isArray(json.data.companies) ? json.data.companies : [],
        keywords: Array.isArray(json.data.keywords) ? json.data.keywords : [],
      }
      setGate(g)
      setCompaniesText(g.companies.join('，'))
      setKeywordsText(g.keywords.join('，'))
    }
  }, [])

  const loadQueue = useCallback(async () => {
    const res = await fetch(`${BASE}/review-queue`)
    const json = await res.json()
    setQueue(Array.isArray(json.data) ? json.data : [])
  }, [])

  const reloadAll = useCallback(async () => {
    try {
      await Promise.all([loadResumes(), loadRules(), loadGate(), loadQueue()])
    } catch (e) {
      console.error('加载失败', e)
    }
  }, [loadResumes, loadRules, loadGate, loadQueue])

  useEffect(() => {
    reloadAll()
  }, [reloadAll])

  // ---------- 简历库 ----------
  const handleUpload = async () => {
    const file = fileRef.current?.files?.[0]
    if (!file) {
      alert('请先选择一张 PNG / JPG / GIF 图片简历（PDF 不支持，需先转成图片）')
      return
    }
    setBusy(true)
    try {
      const fd = new FormData()
      fd.append('file', file)
      fd.append('name', uploadName.trim() || '未命名简历')
      const res = await fetch(`${BASE}/resume`, { method: 'POST', body: fd })
      const json = await res.json()
      if (!json.success) throw new Error(json.message || '上传失败')
      setUploadName('')
      if (fileRef.current) fileRef.current.value = ''
      await loadResumes()
    } catch (e) {
      alert(`上传失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setBusy(false)
    }
  }

  const handleSetDefault = async (id: number) => {
    try {
      const res = await fetch(`${BASE}/resume/${id}/default`, { method: 'POST' })
      const json = await res.json()
      if (!json.success) throw new Error(json.message || '设置失败')
      await loadResumes()
    } catch (e) {
      alert(`设置默认失败：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const handleDeleteResume = async (id: number) => {
    if (!confirm('确定删除这份简历？')) return
    try {
      const res = await fetch(`${BASE}/resume/${id}`, { method: 'DELETE' })
      const json = await res.json()
      if (!json.success) throw new Error(json.message || '删除失败')
      await loadResumes()
      await loadRules()
    } catch (e) {
      alert(`删除失败：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  // ---------- 匹配规则 ----------
  const addRule = () => {
    const fallback = resumes.find((r) => r.isDefault) ?? resumes[0]
    setRules([...rules, { field: '岗位', value: '', resumeId: fallback ? fallback.id : 0 }])
  }

  const updateRule = (index: number, patch: Partial<ResumeRule>) => {
    const next = rules.slice()
    next[index] = { ...next[index], ...patch }
    setRules(next)
  }

  const removeRule = (index: number) => {
    setRules(rules.filter((_, i) => i !== index))
  }

  const saveRules = async () => {
    const cleaned = rules.filter((r) => r.value.trim() !== '')
    if (cleaned.length !== rules.length && !confirm('有规则的关键词为空，保存时会被丢弃，继续？')) return
    setBusy(true)
    try {
      const res = await fetch(`${BASE}/resume-rules`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(cleaned),
      })
      const json = await res.json()
      if (!json.success) throw new Error(json.message || '保存失败')
      setRules(cleaned)
      alert('匹配规则已保存')
    } catch (e) {
      alert(`保存失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setBusy(false)
    }
  }

  // ---------- 人工门 ----------
  const saveGate = async () => {
    setBusy(true)
    try {
      const payload: ReviewGate = {
        enabled: gate.enabled,
        salaryMin: Number(gate.salaryMin) || 0,
        companies: splitList(companiesText),
        keywords: splitList(keywordsText),
      }
      const res = await fetch(`${BASE}/review-gate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      const json = await res.json()
      if (!json.success) throw new Error(json.message || '保存失败')
      setGate(payload)
      alert('人工门设置已保存')
    } catch (e) {
      alert(`保存失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setBusy(false)
    }
  }

  // ---------- 待处理队列 ----------
  const markDone = async (id: number) => {
    try {
      const res = await fetch(`${BASE}/review-queue/${id}/done`, { method: 'POST' })
      const json = await res.json()
      if (!json.success) throw new Error(json.message || '操作失败')
      await loadQueue()
    } catch (e) {
      alert(`操作失败：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const resumeName = (id: number | null): string => {
    if (!id) return '默认简历'
    const found = resumes.find((r) => r.id === id)
    return found ? found.name : `#${id}`
  }

  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiImage className="text-2xl" />}
        title="简历与人工门"
        subtitle="管理多份针对性简历、自动匹配规则，并拦下优质岗位由你亲自投递"
        iconClass="text-white"
        accentBgClass="bg-gradient-to-br from-rose-500 to-orange-500"
        actions={
          <Button onClick={reloadAll} variant="outline" size="sm" type="button">
            <BiRefresh className="mr-1" /> 刷新
          </Button>
        }
      />

      <Tabs defaultValue="resumes" className="space-y-4">
        <TabsList className="grid w-full grid-cols-4 max-w-2xl">
          <TabsTrigger value="resumes">简历库（{resumes.length}）</TabsTrigger>
          <TabsTrigger value="rules">匹配规则（{rules.length}）</TabsTrigger>
          <TabsTrigger value="gate">优质岗人工门</TabsTrigger>
          <TabsTrigger value="queue">待处理（{queue.length}）</TabsTrigger>
        </TabsList>

        {/* ============ 简历库 ============ */}
        <TabsContent value="resumes" className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <BiUpload className="text-primary" /> 上传新简历
              </CardTitle>
              <CardDescription>
                支持 PNG / JPG / GIF，单张不超过 10MB。PDF 需先转成图片；第一份上传的简历自动成为默认。
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="grid gap-4 md:grid-cols-2">
                <div className="space-y-2">
                  <Label htmlFor="resume-file">简历图片</Label>
                  <Input id="resume-file" type="file" accept="image/png,image/jpeg,image/gif" ref={fileRef} />
                </div>
                <div className="space-y-2">
                  <Label htmlFor="resume-name">简历名称（便于区分用途）</Label>
                  <Input
                    id="resume-name"
                    value={uploadName}
                    onChange={(e) => setUploadName(e.target.value)}
                    placeholder="例如：Python后端版 / 测试开发版"
                  />
                </div>
              </div>
              <Button onClick={handleUpload} disabled={busy} type="button">
                <BiPlus className="mr-1" /> 加入简历库
              </Button>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>简历库</CardTitle>
              <CardDescription>
                「默认」这份用于没命中任何规则的普通岗位；命中规则的岗位会改用对应简历。
              </CardDescription>
            </CardHeader>
            <CardContent>
              {resumes.length === 0 ? (
                <p className="text-sm text-muted-foreground py-6 text-center">还没有简历，先上传一份吧。</p>
              ) : (
                <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                  {resumes.map((r) => (
                    <div
                      key={r.id}
                      className="rounded-2xl border border-stroke/50 dark:border-strokedark bg-white/40 dark:bg-blacksection/40 p-3 space-y-3"
                    >
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img
                        src={`${BASE}/resume/${r.id}/content`}
                        alt={r.name}
                        className="w-full h-48 object-contain rounded-xl bg-white/70 dark:bg-white/5"
                      />
                      <div className="flex items-center justify-between gap-2">
                        <div className="min-w-0">
                          <p className="font-medium truncate">{r.name}</p>
                          <p className="text-xs text-muted-foreground truncate">{r.fileName}</p>
                        </div>
                        {r.isDefault && (
                          <span className="shrink-0 rounded-full bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 text-xs px-2 py-1">
                            默认
                          </span>
                        )}
                      </div>
                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          variant={r.isDefault ? 'secondary' : 'outline'}
                          type="button"
                          disabled={r.isDefault}
                          onClick={() => handleSetDefault(r.id)}
                          className="flex-1"
                        >
                          设为默认
                        </Button>
                        <Button
                          size="sm"
                          variant="destructive"
                          type="button"
                          onClick={() => handleDeleteResume(r.id)}
                        >
                          <BiTrash />
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ============ 匹配规则 ============ */}
        <TabsContent value="rules" className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>简历自动匹配规则</CardTitle>
              <CardDescription>
                按「包含」匹配：公司名 / 岗位名 / 岗位描述里出现你填的词，就用对应简历投递；都不命中时用默认简历。
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              {resumes.length === 0 ? (
                <p className="text-sm text-muted-foreground py-4">
                  简历库为空，先去「简历库」上传简历，规则才能选中简历。
                </p>
              ) : (
                <>
                  {rules.length === 0 && (
                    <p className="text-sm text-muted-foreground">还没有规则，点下面「新增一条规则」开始。</p>
                  )}
                  {rules.map((rule, index) => (
                    <div key={index} className="grid gap-3 md:grid-cols-[140px_1fr_200px_auto] items-end">
                      <div className="space-y-1">
                        <Label>匹配字段</Label>
                        <select
                          value={rule.field}
                          onChange={(e) => updateRule(index, { field: e.target.value })}
                          className="h-10 w-full rounded-full border border-white/40 bg-white/5 px-4 text-sm dark:bg-blacksection"
                        >
                          {RULE_FIELDS.map((f) => (
                            <option key={f} value={f}>
                              {f}
                            </option>
                          ))}
                        </select>
                      </div>
                      <div className="space-y-1">
                        <Label>包含关键词</Label>
                        <Input
                          value={rule.value}
                          onChange={(e) => updateRule(index, { value: e.target.value })}
                          placeholder="例如：测试 / 后端 / 字节"
                        />
                      </div>
                      <div className="space-y-1">
                        <Label>使用简历</Label>
                        <select
                          value={rule.resumeId}
                          onChange={(e) => updateRule(index, { resumeId: Number(e.target.value) })}
                          className="h-10 w-full rounded-full border border-white/40 bg-white/5 px-4 text-sm dark:bg-blacksection"
                        >
                          {resumes.map((r) => (
                            <option key={r.id} value={r.id}>
                              {r.name}
                              {r.isDefault ? '（默认）' : ''}
                            </option>
                          ))}
                        </select>
                      </div>
                      <Button variant="destructive" size="icon" type="button" onClick={() => removeRule(index)}>
                        <BiTrash />
                      </Button>
                    </div>
                  ))}
                  <div className="flex gap-2">
                    <Button variant="outline" type="button" onClick={addRule}>
                      <BiPlus className="mr-1" /> 新增一条规则
                    </Button>
                    <Button type="button" onClick={saveRules} disabled={busy}>
                      <BiSave className="mr-1" /> 保存规则
                    </Button>
                  </div>
                </>
              )}
            </CardContent>
          </Card>
        </TabsContent>

        {/* ============ 人工门 ============ */}
        <TabsContent value="gate" className="space-y-6">
          <Card>
            <CardHeader className="flex items-start gap-4">
              <div className="min-w-0 space-y-2">
                <CardTitle className="flex items-center gap-2">
                  <BiLockAlt className="text-primary" /> 优质岗位人工门
                </CardTitle>
                <CardDescription>
                  开启后，命中的优质岗位<b>不会被自动投递</b>，而是记入「待处理」队列，由你亲自挑简历去投。
                </CardDescription>
              </div>
              <button
                type="button"
                aria-label="人工门开关"
                onClick={() => setGate({ ...gate, enabled: !gate.enabled })}
                className={`relative inline-flex h-7 w-14 shrink-0 rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-emerald-400/40 border border-white/30 shadow-[inset_0_1px_0_rgba(255,255,255,.25)] ${
                  gate.enabled ? 'bg-emerald-500/80 hover:bg-emerald-500' : 'bg-white/10 hover:bg-white/15'
                }`}
              >
                <span
                  className={`absolute top-1 left-1 h-5 w-5 rounded-full bg-white shadow transition-transform ${
                    gate.enabled ? 'translate-x-7' : 'translate-x-0'
                  }`}
                />
              </button>
            </CardHeader>
            <CardContent className="space-y-5">
              <div className="space-y-2 max-w-xs">
                <Label htmlFor="salary-min">薪资阈值（K，命中即视为好岗）</Label>
                <Input
                  id="salary-min"
                  type="number"
                  min={0}
                  value={gate.salaryMin}
                  onChange={(e) => setGate({ ...gate, salaryMin: Number(e.target.value) || 0 })}
                  placeholder="例如：15（表示 15K 及以上）"
                />
                <p className="text-xs text-muted-foreground">填 0 表示不按薪资判定。「面议」的岗位薪资按 0 处理。</p>
              </div>

              <div className="space-y-2">
                <Label htmlFor="gate-companies">盯防公司名单</Label>
                <Textarea
                  id="gate-companies"
                  value={companiesText}
                  onChange={(e) => setCompaniesText(e.target.value)}
                  placeholder="用逗号或换行分隔，例如：字节跳动，腾讯，阿里"
                  className="min-h-[90px]"
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="gate-keywords">盯防关键词</Label>
                <Textarea
                  id="gate-keywords"
                  value={keywordsText}
                  onChange={(e) => setKeywordsText(e.target.value)}
                  placeholder="岗位名/描述命中即拦下，例如：算法，大模型，实习生"
                  className="min-h-[90px]"
                />
              </div>

              <Button onClick={saveGate} disabled={busy} type="button">
                <BiSave className="mr-1" /> 保存设置
              </Button>
            </CardContent>
          </Card>

          <Card className="border-primary/20 bg-primary/5">
            <CardContent className="pt-6">
              <div className="flex gap-3">
                <BiInfoCircle className="h-5 w-5 text-primary flex-shrink-0 mt-0.5" />
                <div className="text-sm text-muted-foreground space-y-2">
                  <p>
                    <strong className="text-foreground">判定规则：</strong>
                    薪资 ≥ 阈值 <b>或</b> 公司名命中名单 <b>或</b> 关键词命中，三者任一即算优质岗。
                  </p>
                  <p>
                    <strong className="text-foreground">命中之后：</strong>
                    程序<b>不会</b>替你跟这个岗位打招呼，只把它记进队列。你到「待处理」里看，挑合适简历手动投。
                  </p>
                  <p>
                    <strong className="text-foreground">注意：</strong>
                    关掉开关则恢复原样——所有岗位按规则自动投递（含优质岗）。
                  </p>
                </div>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        {/* ============ 待处理队列 ============ */}
        <TabsContent value="queue" className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>优质岗待处理队列</CardTitle>
              <CardDescription>
                这些岗位命中了人工门、没有被自动投递。投完记得点「标记已处理」，队列只做提醒，不会自己清空。
              </CardDescription>
            </CardHeader>
            <CardContent>
              {queue.length === 0 ? (
                <p className="text-sm text-muted-foreground py-6 text-center">
                  暂无待处理岗位。等你跑起自动投递、遇到命中规则的优质岗，这里就会出现。
                </p>
              ) : (
                <div className="space-y-3">
                  {queue.map((item) => (
                    <div
                      key={item.id}
                      className="flex flex-wrap items-center gap-3 rounded-2xl border border-stroke/50 dark:border-strokedark bg-white/40 dark:bg-blacksection/40 p-4"
                    >
                      <div className="min-w-0 flex-1">
                        <p className="font-medium truncate">
                          {item.company} · {item.jobName}
                        </p>
                        <p className="text-xs text-muted-foreground">
                          薪资 {item.salary || '面议'} ｜ 建议简历：{resumeName(item.suggestedResumeId)} ｜ 发现于{' '}
                          {formatTime(item.createdAt)}
                        </p>
                      </div>
                      {item.jobUrl && (
                        <a
                          href={item.jobUrl}
                          target="_blank"
                          rel="noreferrer"
                          className="text-sm text-primary underline underline-offset-4"
                        >
                          打开岗位
                        </a>
                      )}
                      <Button size="sm" variant="success" type="button" onClick={() => markDone(item.id)}>
                        <BiCheck className="mr-1" /> 标记已处理
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}
