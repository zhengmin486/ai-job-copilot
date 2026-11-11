'use client'

import AnalysisContent from '@/app/boss/analysis/AnalysisContent'
import PageHeader from '@/app/components/PageHeader'
import { BiBriefcase } from 'react-icons/bi'

export default function BossRecordsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        icon={<BiBriefcase className="text-2xl" />}
        title="Boss 投递记录"
        subtitle="查看已投递岗位、投递状态与详情，支持筛选、刷新与导出 CSV"
        iconClass="text-white"
        accentBgClass="bg-indigo-500"
      />
      {/* 复用投递分析的完整能力：KPI、图表、筛选、岗位数据表与导出 */}
      <AnalysisContent showHeader={false} />
    </div>
  )
}
