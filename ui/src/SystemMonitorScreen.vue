<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import autofit from 'autofit.js'
import VChart from 'vue-echarts'
import * as echarts from 'echarts/core'
import { LineChart, GaugeChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { BorderBox12, DigitalFlop, WaterLevelPond } from '@kjgl77/datav-vue3'
import { readStoredSession, type MonitorSnapshot } from './api'

// 页面级注册，避免改变现有控制台对 ECharts 的使用方式。
echarts.use([LineChart, GaugeChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

type MonitorUser = { name: string; ip: string; region: string; status: string }
type MonitorLog = { time: string; level: 'INFO' | 'WARN' | 'ERROR'; message: string }

const emit = defineEmits<{ back: [] }>()
const rootId = 'system-monitor-screen'
const now = ref(new Date())
const online = ref(0)
const concurrent = ref(0)
const waiting = ref(0)
const cpu = ref(0)
const cpuPeak = ref(0)
const memory = ref(0)
const memoryUsed = ref(0)
const memoryTotal = ref(0)
const cpuCores = ref(0)
const streamingRequests = ref(0)
const maxConcurrent = ref(0)
const queueCapacity = ref(0)
const uptimeSeconds = ref(0)
const selectedLevel = ref<'ALL' | MonitorLog['level']>('ALL')
const labels = ref<string[]>([])
const concurrencyHistory = ref<number[]>([])
const cpuHistory = ref<number[]>([])
const memoryHistory = ref<number[]>([])
const todayHistory = ref<number[]>([])
const users = ref<MonitorUser[]>([])
const logs = ref<MonitorLog[]>([])
let socket: WebSocket | undefined
let reconnectTimer: ReturnType<typeof setTimeout> | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined
let reconnectDelay = 1000
let disposed = false

const uptime = computed(() => {
  const days = Math.floor(uptimeSeconds.value / 86400)
  const hours = Math.floor(uptimeSeconds.value % 86400 / 3600)
  const minutes = Math.floor(uptimeSeconds.value % 3600 / 60)
  return `${days}天 ${String(hours).padStart(2, '0')}时 ${String(minutes).padStart(2, '0')}分`
})
const timeText = computed(() => new Intl.DateTimeFormat('zh-CN', { hourCycle: 'h23', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(now.value))
const dateText = computed(() => new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' }).format(now.value))
const queueTone = computed(() => queueCapacity.value > 0 && waiting.value / queueCapacity.value >= .8 ? 'danger' : queueCapacity.value > 0 && waiting.value / queueCapacity.value >= .5 ? 'warning' : '')
const concurrencyRate = computed(() => maxConcurrent.value > 0 ? Math.min(100, concurrent.value / maxConcurrent.value * 100) : 0)
const queueRate = computed(() => queueCapacity.value > 0 ? Math.min(100, waiting.value / queueCapacity.value * 100) : 0)
const streamingRate = computed(() => concurrent.value > 0 ? Math.min(100, streamingRequests.value / concurrent.value * 100) : 0)
const onlineRate = computed(() => Math.min(100, online.value / 100 * 100))
const maxConcurrentLabel = computed(() => maxConcurrent.value > 0 ? maxConcurrent.value : '无限制')
const queueCapacityLabel = computed(() => queueCapacity.value > 0 ? queueCapacity.value : '未开启')
const filteredLogs = computed(() => selectedLevel.value === 'ALL' ? logs.value : logs.value.filter(log => log.level === selectedLevel.value))
const flopStyle = { fontSize: 38, fill: '#e7f8ff', fontWeight: 'bold' }
const waterConfig = computed(() => ({ data: [memory.value], shape: 'roundRect', waveNum: 2, waveHeight: 18, colors: ['#18c6e8', '#0d437e'], formatter: '{value}%', waveOpacity: 0.46 }))

function chartOption(data: number[], color: string, name: string, area = true) {
  return {
    animationDurationUpdate: 280,
    grid: { left: 34, right: 14, top: 26, bottom: 23 },
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(5, 21, 43, .94)', borderColor: '#25567a', textStyle: { color: '#dff8ff' } },
    xAxis: { type: 'category', boundaryGap: false, data: labels.value, axisTick: { show: false }, axisLine: { lineStyle: { color: '#1c4565' } }, axisLabel: { color: '#6f9fbc', fontSize: 10, interval: 14 } },
    yAxis: { type: 'value', scale: true, splitNumber: 3, axisLabel: { color: '#6f9fbc', fontSize: 10 }, splitLine: { lineStyle: { color: 'rgba(44, 103, 136, .28)', type: 'dashed' } } },
    series: [{ name, type: 'line', data, smooth: 0.35, showSymbol: false, lineStyle: { color, width: 2 }, itemStyle: { color }, areaStyle: area ? { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: `${color}88` }, { offset: 1, color: `${color}05` }] } } : undefined }]
  }
}
const concurrencyOption = computed(() => chartOption(concurrencyHistory.value, '#20d6f6', '实时并发'))
const cpuOption = computed(() => ({ ...chartOption(cpuHistory.value, '#38d89f', 'CPU 负载'), series: [
  { name: 'CPU 负载', type: 'line', data: cpuHistory.value, smooth: .35, showSymbol: false, lineStyle: { color: '#38d89f', width: 2 }, areaStyle: { color: 'rgba(56,216,159,.08)' } },
  { name: '峰值核心', type: 'line', data: cpuHistory.value.map(value => Math.min(100, value + 17)), smooth: .35, showSymbol: false, lineStyle: { color: '#f2b74d', width: 1.5, type: 'dashed' } }
]}))
const memoryOption = computed(() => chartOption(memoryHistory.value, '#9b8cff', '内存占用'))
const historyOption = computed(() => ({ ...chartOption(todayHistory.value, '#f0b54a', '今日并发'), xAxis: { type: 'category', boundaryGap: false, data: labels.value, axisTick: { show: false }, axisLine: { lineStyle: { color: '#1c4565' } }, axisLabel: { color: '#6f9fbc', fontSize: 10, interval: 'auto' } } }))
function gaugeOption(value: number, color: string, label: string) {
  return { series: [{ type: 'gauge', startAngle: 215, endAngle: -35, min: 0, max: 100, progress: { show: true, width: 11, roundCap: true, itemStyle: { color } }, axisLine: { lineStyle: { width: 11, color: [[1, '#173b58']] } }, pointer: { show: false }, axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false }, detail: { valueAnimation: true, formatter: `{value|${value.toFixed(1)}%}\n{name|${label}}`, rich: { value: { color: '#e9fbff', fontSize: 20, fontWeight: 'bold', lineHeight: 28 }, name: { color: '#7fa9c0', fontSize: 10, lineHeight: 15 } } }, data: [{ value }] }] }
}
const cpuGauge = computed(() => gaugeOption(cpu.value, '#31d89b', '综合使用率'))
const peakGauge = computed(() => gaugeOption(cpuPeak.value, '#f1b64a', '峰值核心负载'))

function applySnapshot(snapshot: MonitorSnapshot) {
  online.value = snapshot.online
  streamingRequests.value = snapshot.streamingRequests
  concurrent.value = snapshot.concurrent
  waiting.value = snapshot.waiting
  maxConcurrent.value = snapshot.maxConcurrent
  queueCapacity.value = snapshot.queueCapacity
  cpu.value = snapshot.cpu
  cpuPeak.value = snapshot.processCpu
  memory.value = snapshot.memory
  memoryUsed.value = snapshot.memoryUsedGb
  memoryTotal.value = snapshot.memoryTotalGb
  cpuCores.value = snapshot.cpuCores
  uptimeSeconds.value = snapshot.uptimeSeconds
  users.value = snapshot.users
  logs.value = snapshot.logs
  const lastMinute = snapshot.history.slice(-60)
  labels.value = lastMinute.map(point => new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(point.time))
  concurrencyHistory.value = lastMinute.map(point => point.concurrent)
  todayHistory.value = snapshot.history.map(point => point.concurrent)
  cpuHistory.value = [...cpuHistory.value.slice(-59), snapshot.cpu]
  memoryHistory.value = [...memoryHistory.value.slice(-59), snapshot.memory]
}
function connectMonitorSocket() {
  const token = readStoredSession()?.token
  if (!token) { logs.value = [{ time: '--:--:--', level: 'ERROR', message: '未找到管理员登录凭据，无法建立实时监控连接。' }]; return }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  socket = new WebSocket(`${protocol}//${window.location.host}/api/admin/monitor/ws`, token)
  socket.onopen = () => { reconnectDelay = 1000 }
  socket.onmessage = event => { try { applySnapshot(JSON.parse(String(event.data)) as MonitorSnapshot) } catch { /* Ignore malformed monitoring frames. */ } }
  socket.onclose = () => {
    socket = undefined
    if (disposed) return
    reconnectTimer = setTimeout(connectMonitorSocket, reconnectDelay)
    reconnectDelay = Math.min(reconnectDelay * 2, 15_000)
  }
  socket.onerror = () => socket?.close()
}
function leaveScreen() { emit('back') }

onMounted(() => {
  autofit.init({ el: `#${rootId}`, dw: 1920, dh: 1080, resize: true, transition: 0, allowScroll: false })
  clockTimer = setInterval(() => { now.value = new Date() }, 1000)
  connectMonitorSocket()
})
onUnmounted(() => { disposed = true; if (reconnectTimer) clearTimeout(reconnectTimer); socket?.close(); if (clockTimer) clearInterval(clockTimer); autofit.off() })
</script>

<template>
  <main :id="rootId" class="monitor-screen">
    <header class="monitor-header">
      <div class="header-side"><span class="header-kicker">SYSTEM UPTIME</span><b>{{ uptime }}</b><i class="pulse-dot"></i></div>
      <div class="header-title"><span class="title-line"></span><h1>实时系统运行监控中心</h1><small>AI NEXUS · REALTIME OPERATIONS COMMAND</small><span class="title-line reverse"></span></div>
      <div class="header-side align-right"><span>{{ dateText }}</span><b class="clock">{{ timeText }}</b><button type="button" class="exit-button" @click="leaveScreen">返回原系统 <span>↗</span></button></div>
    </header>

    <section class="monitor-grid">
      <aside class="left-column">
        <BorderBox12 class="tech-panel active-user-panel"><div class="panel-body">
          <div class="panel-title"><div><span class="section-index">01</span><h2>实时活动用户</h2></div><div class="online-total"><span>ONLINE</span><b>{{ online }}</b></div></div>
          <div class="user-table-head"><span>用户身份</span><span>登录 IP</span><span>归属地域</span><span>状态</span></div>
          <div class="user-list"><div v-for="user in users" :key="user.ip" class="user-row"><span class="user-name">{{ user.name }}</span><span>{{ user.ip }}</span><span>{{ user.region }}</span><span :class="['status-dot', user.status === '活跃' ? 'is-active' : '']">{{ user.status }}</span></div></div>
          <div class="table-foot"><i></i><span>实时会话每秒刷新</span><i></i></div>
        </div></BorderBox12>

        <BorderBox12 class="tech-panel queue-panel"><div class="panel-body">
          <div class="panel-title"><div><span class="section-index">02</span><h2>实时并发 & 等待队列</h2></div><span class="source-label">LIVE</span></div>
          <div class="flop-row"><div class="flop-card"><span>当前并发数</span><DigitalFlop :config="{ number: [concurrent], style: flopStyle, content: '{nt}' }" /></div><div :class="['flop-card', queueTone]"><span>等待队列</span><DigitalFlop :config="{ number: [waiting], style: { ...flopStyle, fill: queueTone ? '#ffbe66' : '#e7f8ff' }, content: '{nt}' }" /></div></div>
          <div class="chart-caption"><span>近 60 秒并发波动</span><b>{{ concurrent }} <small>req/s</small></b></div><VChart class="mini-chart" :option="concurrencyOption" autoresize />
        </div></BorderBox12>
      </aside>

      <section class="center-column">
        <BorderBox12 class="tech-panel cpu-panel"><div class="panel-body">
          <div class="panel-title"><div><span class="section-index">03</span><h2>CPU 综合监控</h2></div><span class="frequency-label">{{ cpuCores }} LOGICAL CORES</span></div>
          <div class="cpu-content"><div class="core-grid"><div class="core-item"><span>LOGICAL CORES</span><b>{{ cpuCores }}<small>个</small></b><i :style="{ width: '100%' }"></i></div><div class="core-item"><span>系统 CPU</span><b>{{ cpu.toFixed(1) }}<small>%</small></b><i :style="{ width: `${cpu}%` }"></i></div><div class="core-item"><span>JVM PROCESS</span><b>{{ cpuPeak.toFixed(1) }}<small>%</small></b><i :style="{ width: `${cpuPeak}%` }"></i></div><div class="core-item"><span>STREAMS</span><b>{{ online }}<small>users</small></b><i :style="{ width: `${Math.min(100, online)}%` }"></i></div></div>
            <div class="gauges"><VChart class="gauge-chart" :option="cpuGauge" autoresize/><VChart class="gauge-chart" :option="peakGauge" autoresize/></div>
            <div class="trend-block"><div class="chart-caption"><span>负载动态曲线</span><div class="legend-dot"><i></i>综合 <i class="peak"></i>峰值</div></div><VChart class="cpu-chart" :option="cpuOption" autoresize/></div>
          </div>
        </div></BorderBox12>

        <BorderBox12 class="tech-panel memory-panel"><div class="panel-body">
          <div class="panel-title"><div><span class="section-index">04</span><h2>内存使用率</h2></div><span class="source-label">MEMORY</span></div>
          <div class="memory-content"><div class="water-wrap"><WaterLevelPond :config="waterConfig"/></div><div class="memory-details"><div><span>已用 / 总容量</span><b>{{ memoryUsed.toFixed(1) }} <small>/ {{ memoryTotal.toFixed(1) }} GB</small></b></div><div><span>可用内存</span><b>{{ Math.max(0, memoryTotal - memoryUsed).toFixed(1) }} <small>GB</small></b></div><div class="memory-meter"><i :style="{ width: `${memory}%` }"></i></div></div><VChart class="memory-chart" :option="memoryOption" autoresize/></div>
        </div></BorderBox12>

        <BorderBox12 class="tech-panel node-panel"><div class="panel-body node-body"><div class="panel-title"><div><span class="section-index">05</span><h2>网关承载状态</h2></div><span class="source-label">LIVE SNAPSHOT</span></div><div class="capacity-grid"><article><span class="capacity-led is-online"></span><small>当前并发</small><b>{{ concurrent }}<em>/ {{ maxConcurrentLabel }}</em></b><i><strong :style="{ width: `${concurrencyRate}%` }"></strong></i></article><article><span :class="['capacity-led', waiting ? 'is-warning' : 'is-online']"></span><small>等待队列</small><b>{{ waiting }}<em>/ {{ queueCapacityLabel }}</em></b><i><strong :style="{ width: `${queueRate}%` }"></strong></i></article><article><span class="capacity-led is-online"></span><small>流式请求</small><b>{{ streamingRequests }}</b><i><strong :style="{ width: `${streamingRate}%` }"></strong></i></article><article><span class="capacity-led is-online"></span><small>在线会话</small><b>{{ online }}</b><i><strong :style="{ width: `${onlineRate}%` }"></strong></i></article></div></div></BorderBox12>
      </section>

      <aside class="right-column">
        <BorderBox12 class="tech-panel history-panel"><div class="panel-body"><div class="panel-title"><div><span class="section-index">06</span><h2>今日历史并发趋势</h2></div><span class="source-label">24 HOURS</span></div><div class="history-stat"><span>采样峰值</span><b>{{ todayHistory.length ? Math.max(...todayHistory) : 0 }}</b><small>进程启动以来采样</small></div><VChart class="history-chart" :option="historyOption" autoresize/></div></BorderBox12>
        <BorderBox12 class="tech-panel log-panel"><div class="panel-body"><div class="panel-title"><div><span class="section-index">07</span><h2>系统警告与实时日志流</h2></div><span class="log-live">STREAMING</span></div><div class="log-filter"><button v-for="level in ['ALL', 'INFO', 'WARN', 'ERROR'] as const" :key="level" :class="{ active: selectedLevel === level, [level.toLowerCase()]: true }" type="button" @click="selectedLevel = level">{{ level }}</button></div><div class="log-list"><article v-for="log in filteredLogs" :key="`${log.time}-${log.message}`" :class="['log-line', log.level.toLowerCase()]"><time>{{ log.time }}</time><b>{{ log.level }}</b><span>{{ log.message }}</span></article></div></div></BorderBox12>
      </aside>
    </section>
  </main>
</template>

<style scoped>
.monitor-screen { width: 1920px; height: 1080px; overflow: hidden; color: #d9f5ff; background: #061525; font-family: "Microsoft YaHei", "PingFang SC", sans-serif; letter-spacing: 0; position: relative; isolation: isolate; }
.monitor-screen::before { content: ""; position: absolute; inset: 0; pointer-events: none; opacity: .7; background-image: linear-gradient(rgba(44, 141, 186, .075) 1px, transparent 1px), linear-gradient(90deg, rgba(44, 141, 186, .065) 1px, transparent 1px), radial-gradient(ellipse at 50% 100%, rgba(0, 114, 159, .2), transparent 52%); background-size: 48px 48px, 48px 48px, auto; z-index: -1; }
.monitor-header { height: 112px; padding: 18px 44px 0; box-sizing: border-box; display: grid; grid-template-columns: 1fr 1.35fr 1fr; align-items: start; border-bottom: 1px solid rgba(57, 147, 193, .38); }
.header-side { color: #83b8cf; font-size: 14px; display: flex; align-items: center; gap: 12px; padding-top: 15px; }.header-side b { color: #e5fbff; font-size: 18px; font-family: Consolas, monospace; }.header-side.align-right { justify-content: flex-end; gap: 16px; }.header-side .clock { font-size: 24px; letter-spacing: 2px; }.header-kicker { color: #5b92ac; font-family: Consolas, monospace; font-size: 11px; }.pulse-dot { width: 8px; height: 8px; border-radius: 50%; background: #2de4a7; box-shadow: 0 0 12px #2de4a7; animation: pulse 1.6s infinite; }
.header-title { display: flex; align-items: center; justify-content: center; gap: 13px; flex-wrap: wrap; }.header-title h1 { width: 100%; order: -1; margin: 0; text-align: center; color: #effcff; font-size: 32px; font-weight: 700; text-shadow: 0 0 18px rgba(34, 202, 249, .45); }.header-title small { color: #4ca3c6; font-family: Consolas, monospace; font-size: 10px; letter-spacing: 2px; }.title-line { width: 88px; height: 1px; background: linear-gradient(90deg, transparent, #35c7e8); }.title-line.reverse { background: linear-gradient(90deg, #35c7e8, transparent); }.exit-button { margin-left: 4px; color: #bdefff; border: 1px solid #2b91bd; background: rgba(19, 93, 126, .18); height: 30px; padding: 0 11px; font: inherit; cursor: pointer; }.exit-button:hover { background: rgba(31, 145, 188, .32); }.exit-button span { color: #44d7ef; font-size: 16px; }
.monitor-grid { box-sizing: border-box; padding: 18px 28px 25px; height: 968px; display: grid; grid-template-columns: 28% 44% 28%; gap: 14px; }.left-column, .right-column, .center-column { min-width: 0; display: grid; gap: 14px; }.left-column { grid-template-rows: 60% calc(40% - 14px); }.center-column { grid-template-rows: 45% 28% calc(27% - 28px); }.right-column { grid-template-rows: 45% calc(55% - 14px); }.tech-panel { min-height: 0; background: rgba(6, 25, 43, .64); }.panel-body { height: 100%; box-sizing: border-box; padding: 20px 26px 18px; }.panel-title { display: flex; justify-content: space-between; align-items: center; height: 29px; border-bottom: 1px solid rgba(51, 132, 166, .34); padding-bottom: 11px; }.panel-title > div { display: flex; align-items: center; gap: 9px; }.panel-title h2 { margin: 0; color: #e0f6ff; font-size: 17px; font-weight: 600; }.section-index { color: #20d2f0; font-family: Consolas, monospace; font-weight: bold; font-size: 12px; }.source-label, .frequency-label { color: #5facc7; font-family: Consolas, monospace; font-size: 10px; letter-spacing: 1px; }.online-total { display: flex; align-items: baseline; gap: 7px; }.online-total span { font-family: Consolas, monospace; color: #6da4bb; font-size: 10px; }.online-total b { font: bold 25px Consolas, monospace; color: #24d8f2; }
.user-table-head, .user-row { display: grid; grid-template-columns: 1.12fr 1.18fr 1.13fr .62fr; align-items: center; column-gap: 7px; }.user-table-head { margin: 13px 0 4px; color: #6397ae; font-size: 11px; }.user-row { height: 47px; border-bottom: 1px solid rgba(46, 113, 144, .22); font: 12px Consolas, monospace; color: #94c6d9; }.user-name { color: #d5f5ff; }.status-dot { color: #7a9bad; font-family: "Microsoft YaHei", sans-serif; }.status-dot::before { content: ""; width: 5px; height: 5px; display: inline-block; margin-right: 5px; border-radius: 50%; background: #5d7480; }.status-dot.is-active { color: #60e4b6; }.status-dot.is-active::before { background: #30dda7; box-shadow: 0 0 7px #30dda7; }.table-foot { display: flex; align-items: center; justify-content: center; gap: 7px; padding-top: 12px; color: #54869d; font-size: 10px; }.table-foot i { height: 1px; width: 42px; background: #255d75; }
.flop-row { height: 88px; display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 13px 0 7px; }.flop-card { padding: 8px 10px; background: linear-gradient(135deg, rgba(22, 86, 117, .35), rgba(8, 37, 60, .3)); border-left: 2px solid #22c9ed; }.flop-card.danger { border-left-color: #ff9d48; }.flop-card.warning { border-left-color: #f5d35e; }.flop-card > span { color: #78aabd; font-size: 11px; }.chart-caption { height: 24px; display: flex; justify-content: space-between; align-items: center; color: #7baac0; font-size: 11px; }.chart-caption b { color: #e1f9ff; font: 15px Consolas, monospace; }.chart-caption small { color: #58859a; font-size: 10px; }.mini-chart { height: 130px; }
.cpu-content { height: calc(100% - 53px); display: grid; grid-template-columns: 29% 31% 40%; align-items: center; }.core-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 11px 14px; padding-right: 18px; }.core-item { position: relative; padding-bottom: 6px; border-bottom: 1px solid rgba(47, 108, 138, .4); }.core-item span { display: block; color: #6b9fb5; font: 9px Consolas, monospace; }.core-item b { display: block; padding-top: 3px; color: #d5f6ff; font: 14px Consolas, monospace; }.core-item small { margin-left: 2px; color: #6398ad; font-size: 9px; }.core-item i { position: absolute; left: 0; bottom: -1px; height: 2px; background: #24d8f1; box-shadow: 0 0 5px #24d8f1; }.gauges { height: 100%; display: grid; grid-template-columns: 1fr 1fr; }.gauge-chart { height: 178px; align-self: center; }.trend-block { height: 100%; padding-left: 12px; border-left: 1px solid rgba(45, 110, 140, .26); }.legend-dot { color: #6b9bb0; font-size: 10px; }.legend-dot i { display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: #38d89f; margin-right: 3px; }.legend-dot i.peak { background: #f2b74d; margin-left: 8px; }.cpu-chart { height: 158px; }
.memory-content { height: calc(100% - 51px); display: grid; grid-template-columns: 23% 28% 49%; align-items: center; }.water-wrap { width: 108px; height: 108px; margin: auto; }.memory-details { padding: 0 16px; border-right: 1px solid rgba(45, 110, 140, .26); }.memory-details > div:not(.memory-meter) { margin-bottom: 11px; }.memory-details span { display: block; color: #6396ad; font-size: 10px; }.memory-details b { color: #e5faff; font: 19px Consolas, monospace; }.memory-details small { color: #7ba6ba; font-size: 10px; }.memory-meter { height: 4px; background: #17425f; }.memory-meter i { display: block; height: 100%; background: linear-gradient(90deg, #497fff, #a494ff); }.memory-chart { height: 136px; }
.node-body { padding-top: 16px; }.capacity-grid { height: calc(100% - 49px); display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; align-items: center; }.capacity-grid article { position: relative; height: 76px; box-sizing: border-box; padding: 12px; border: 1px solid #215b76; background: rgba(13, 68, 91, .22); }.capacity-led { display: inline-block; width: 6px; height: 6px; margin-right: 5px; border-radius: 50%; }.capacity-led.is-online { background: #33e5af; box-shadow: 0 0 8px #33e5af; }.capacity-led.is-warning { background: #f4bd4f; box-shadow: 0 0 8px #f4bd4f; }.capacity-grid small { color: #6f9fb4; font-size: 10px; }.capacity-grid b { display: block; margin: 7px 0 8px; color: #d7f8ff; font: bold 19px Consolas, monospace; }.capacity-grid em { color: #6c9db2; font-size: 10px; font-style: normal; }.capacity-grid i { display: block; height: 3px; overflow: hidden; background: #17425f; }.capacity-grid strong { display: block; height: 100%; background: linear-gradient(90deg, #25cde9, #4de5ac); }
.history-stat { display: flex; align-items: baseline; gap: 10px; margin-top: 17px; }.history-stat span { color: #709db2; font-size: 11px; }.history-stat b { color: #f0c65a; font: bold 30px Consolas, monospace; }.history-stat small { color: #5d8da3; font: 10px Consolas, monospace; }.history-chart { height: 244px; }
.log-live { color: #3be4b3; font: 10px Consolas, monospace; }.log-filter { display: flex; gap: 7px; padding: 13px 0 9px; }.log-filter button { background: transparent; border: 1px solid #245671; color: #6495aa; padding: 3px 10px; font: 10px Consolas, monospace; cursor: pointer; }.log-filter button.active { color: #dff9ff; border-color: #29cbe8; background: rgba(28, 150, 191, .16); }.log-filter button.active.warn { color: #ffc05d; border-color: #bd8432; }.log-filter button.active.error { color: #ff7783; border-color: #a64d58; }.log-list { height: 378px; overflow: hidden; }.log-line { display: grid; grid-template-columns: 57px 42px 1fr; gap: 8px; padding: 9px 4px; border-bottom: 1px solid rgba(41, 99, 128, .22); color: #8eb8ca; font: 10px Consolas, monospace; }.log-line time { color: #5d91a9; }.log-line b { color: #49cce5; font-size: 10px; }.log-line.warn b { color: #f8bc53; }.log-line.error b { color: #ff7882; }.log-line span { color: #b3d2df; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
@keyframes pulse { 50% { opacity: .4; box-shadow: 0 0 3px #2de4a7; } } @keyframes flow { 50% { transform: translateX(8px) rotate(45deg); opacity: .4; } }
</style>
