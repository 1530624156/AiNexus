import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'

const TOKEN_KEY = 'ainexus.console.token'
const USERNAME_KEY = 'ainexus.console.username'
const ROLE_KEY = 'ainexus.console.role'
export const SESSION_EXPIRED_EVENT = 'ainexus:session-expired'

export type Session = { token: string; username: string; role: 'ADMIN' | 'USER' }

type RenewableRequestConfig = AxiosRequestConfig & { _ainexusRenewed?: boolean }

function storedValue(key: string) {
  return localStorage.getItem(key) ?? sessionStorage.getItem(key)
}

export function readStoredSession(): Session | null {
  const token = storedValue(TOKEN_KEY)
  const username = storedValue(USERNAME_KEY)
  const role = storedValue(ROLE_KEY)
  return token && username && (role === 'ADMIN' || role === 'USER') ? { token, username, role } : null
}

export function saveStoredSession(session: Session) {
  sessionExpired = false
  localStorage.setItem(TOKEN_KEY, session.token)
  localStorage.setItem(USERNAME_KEY, session.username)
  localStorage.setItem(ROLE_KEY, session.role)
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(USERNAME_KEY)
  sessionStorage.removeItem(ROLE_KEY)
}

export function clearStoredSession() {
  for (const storage of [localStorage, sessionStorage]) {
    storage.removeItem(TOKEN_KEY)
    storage.removeItem(USERNAME_KEY)
    storage.removeItem(ROLE_KEY)
  }
}

export const api = axios.create({ baseURL: '/api', timeout: 20_000 })
api.interceptors.request.use((config) => {
  const token = storedValue(TOKEN_KEY)
  if (token) {
    const authorization = `Bearer ${token}`
    if (typeof config.headers.set === 'function') config.headers.set('Authorization', authorization)
    else config.headers.Authorization = authorization
  }
  return config
})

export type LoginOptions = { githubLoginOnly: boolean }
let renewingSession: Promise<void> | null = null
let sessionExpired = false

function expireSession() {
  if (sessionExpired) return
  sessionExpired = true
  clearStoredSession()
  window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT))
}

function renewSession() {
  renewingSession ??= api.post<Session>('/auth/renew')
    .then(({ data }) => saveStoredSession(data))
    .finally(() => { renewingSession = null })
  return renewingSession
}

api.interceptors.response.use(undefined, async (error: AxiosError) => {
  const config = error.config as RenewableRequestConfig | undefined
  const path = config?.url ?? ''
  const canRenew = error.response?.status === 401
    && !!config
    && !config._ainexusRenewed
    && !!storedValue(TOKEN_KEY)
    && !path.startsWith('/auth/login')
    && !path.startsWith('/auth/renew')

  if (!canRenew) {
    if (error.response?.status === 401 && config?._ainexusRenewed) expireSession()
    throw error
  }

  config._ainexusRenewed = true
  try {
    await renewSession()
    return api.request(config)
  } catch {
    expireSession()
    throw error
  }
})

export type Quota = { userGroup: string; period: string; personalQuota?: number | null; personalUsed: number; personalRemaining?: number | null; groupQuota?: number | null; groupUsed: number; groupRemaining?: number | null }
export type Account = { id: number; username: string; remark?: string | null; status: number; quotas: Quota[]; createTime?: string }
export type ModelBinding = { id?: number; modelName: string; upstreamModelName?: string | null; status?: number; remark?: string | null }
export type Channel = { id: number; name: string; provider: string; protocol: string; supportedProtocols?: string[]; baseUrl: string; priority: number; weight: number; maxConcurrent: number; failureCount: number; defaultTestModel?: string | null; status: number; remark?: string | null; models: ModelBinding[] }
export type AuditLog = { id: number; requestId?: string; appName?: string; apiKeyName?: string; userName?: string; userGroupNames?: string; modelGroupNames?: string; requestModel?: string; upstreamModel?: string; targetChannel?: string; provider?: string; upstreamAccount?: string; apiType?: string; stream?: number; upstreamEndpoint?: string; protocolPath?: string; inputTokens?: number; outputTokens?: number; totalTokens?: number; cacheTokens?: number; statusCode: number; upstreamStatusCode?: number; latencyMs: number; firstTokenMs?: number; queueMs?: number; upstreamLatencyMs?: number; errorCode?: string; errorMsg?: string; createTime: string }
export type ApiKey = { id: number; appName: string; tokenMasked: string; status: number; remark?: string | null; createTime?: string }
export type Usage = { requests: number; errors: number; inputTokens: number; outputTokens: number; totalTokens: number; cacheTokens: number; cacheHitRate: number; apiKeys: string[]; models: string[]; quotas: Quota[] }
export type ModelCatalog = { baseUrl: string; models: string[] }
export type UsageTotals = { requests: number; inputTokens: number; outputTokens: number; totalTokens: number; cacheTokens: number; cacheHitRate: number }
export type UsageDistribution = { name: string; requests: number; totalTokens: number; cacheTokens: number; cacheHitRate: number }
export type AdminUsage = { totals: UsageTotals; providers: UsageDistribution[]; channels: UsageDistribution[]; upstreamAccounts: UsageDistribution[]; modelGroups: UsageDistribution[]; models: UsageDistribution[]; userGroups: UsageDistribution[]; users: UsageDistribution[] }
export type UsageFilters = { users: string[]; providers: string[]; apiTypes: string[] }
export type UsageRank = { rank: number; username: string; requests: number; totalTokens: number; cacheTokens: number; cacheHitRate: number }
export type UsageRanking = { rankings: UsageRank[]; totalTokens: number; totalUsers: number }
export type TokenActivityDay = { date: string; totalTokens: number; requests: number }
export type TokenActivity = { startDate: string; endDate: string; totalTokens: number; totalRequests: number; activeDays: number; days: TokenActivityDay[] }
export type GatewaySettings = { accessUrl: string; protocolConversionEnabled: boolean; maxConcurrent: number; queueCapacity: number; nonStreamTimeoutSeconds: number; streamIdleTimeoutSeconds: number; maxPayloadMb: number; githubLoginOnly: boolean }
export type GithubStarSettings = { enabled: boolean; repository?: string | null; publicBaseUrl?: string | null; oauthClientId?: string | null; oauthClientSecret?: string | null; dailyCheckTime?: string | null }
export type GithubBinding = { enabled: boolean; repository?: string | null; bound: boolean; githubLogin?: string | null; starVerifiedAt?: string | null; refreshable?: boolean; accessTokenExpiresAt?: string | null; refreshTokenExpiresAt?: string | null }
export type GithubStarIssue = { username: string; githubLogin?: string | null; reason: string }
export type GithubStarCheckResult = { checkedUsers: number; verifiedBindings: number; disabledKeys: number; unverified: GithubStarIssue[] }
export type Overview = { gateway: { activeUsers: number; streamingRequests: number; concurrent: number; maxConcurrent: number; waiting: number; queueCapacity: number }; userGroups: { name: string; active: number; limit: number }[]; channels: { id: number; name: string; provider: string; priority: number; active: number; limit: number; health: string }[]; usage: { tokens: number; requests: number; totalActiveUsers: number }; history: { time: string; concurrent: number; waiting: number; activeUsers: number; streams: number }[] }
export type ModelGroup = { id: number; name: string; models: string[]; remark?: string | null; createTime?: string; updateTime?: string }
export type NamedOption = { id: number; name: string }
export type UserGroup = { id: number; name: string; maxConcurrent: number; apiKeyMaxConcurrent: number; tokenQuota: number; quotaPeriod: 'DAY' | 'WEEK' | 'MONTH' | 'YEAR'; memberTokenQuota: number; memberMaxConcurrent: number; allowTimeStart?: string | null; allowTimeEnd?: string | null; remark?: string | null; memberIds: number[]; memberNames: string[]; modelGroupIds: number[]; modelGroupNames: string[]; usedPromptTokens: number; remainingTokenQuota?: number | null; createTime?: string; updateTime?: string }

export const authApi = {
  login: (body: { username: string; password: string }) => api.post<Session>('/auth/login', body),
  loginOptions: () => api.get<LoginOptions>('/auth/login-options'),
  githubAuthorize: () => api.get<{ authorizationUrl: string }>('/console/github/login-authorize'),
  me: () => api.get<Omit<Session, 'token'>>('/auth/me'),
}
export const adminApi = {
  dashboard: () => api.get<{ todayRequests: number; todayErrors: number; enabledChannels: number }>('/admin/dashboard'),
  changeAdminPassword: (body: { currentPassword: string; newPassword: string; confirmPassword: string }) => api.post('/admin/system/change-password', body),
  githubStarSettings: () => api.get<GithubStarSettings>('/admin/github-star'),
  updateGithubStarSettings: (body: GithubStarSettings) => api.put<GithubStarSettings>('/admin/github-star', body),
  runGithubStarCheck: () => api.post<GithubStarCheckResult>('/admin/github-star/check'),
  overview: (range: string) => api.get<Overview>('/admin/overview', { params: { range } }),
  gatewaySettings: () => api.get<GatewaySettings>('/admin/gateway-settings'),
  updateGatewaySettings: (body: GatewaySettings) => api.put<GatewaySettings>('/admin/gateway-settings', body),
  usage: (params: { user?: string; provider?: string; apiType?: string; range: string; startDate?: string; endDate?: string }) => api.get<AdminUsage>('/admin/usage', { params }),
  usageFilters: () => api.get<UsageFilters>('/admin/usage/filters'),
  tokenActivity: (params: { user?: string; provider?: string; apiType?: string; range: string; startDate?: string; endDate?: string }) => api.get<TokenActivity>('/admin/usage/token-activity', { params }),
  usageRanking: () => api.get<UsageRanking>('/console/usage-ranking'),
  channels: () => api.get<Channel[]>('/admin/channels'),
  createChannel: (body: any) => api.post<Channel>('/admin/channels', body),
  updateChannel: (id: number, body: any) => api.put<Channel>(`/admin/channels/${id}`, body),
  deleteChannel: (id: number) => api.delete(`/admin/channels/${id}`),
  channelStatus: (id: number, status: number) => api.patch(`/admin/channels/${id}/status`, { status }),
  testChannel: (id: number, body: { modelNames?: string[]; prompt?: string }) => api.post<any[]>(`/admin/channels/${id}/test`, body, { timeout: 180_000 }),
  discoverModels: (body: { baseUrl: string; apiKey: string; protocol: string; protocolConfig?: string }) => api.post<{ id: string; ownedBy?: string }[]>('/admin/channels/discover-models', body),
  discoverSavedChannelModels: (id: number) => api.post<{ id: string; ownedBy?: string }[]>(`/admin/channels/${id}/discover-models`),
  modelGroups: () => api.get<ModelGroup[]>('/admin/model-groups'),
  availableGroupModels: () => api.get<string[]>('/admin/model-groups/available-models'),
  createModelGroup: (body: { name: string; models: string[]; remark?: string }) => api.post<ModelGroup>('/admin/model-groups', body),
  updateModelGroup: (id: number, body: { name: string; models: string[]; remark?: string }) => api.put<ModelGroup>(`/admin/model-groups/${id}`, body),
  deleteModelGroup: (id: number) => api.delete(`/admin/model-groups/${id}`),
  userGroups: () => api.get<UserGroup[]>('/admin/user-groups'),
  availableGroupMembers: () => api.get<NamedOption[]>('/admin/user-groups/available-members'),
  availableAuthorizedModelGroups: () => api.get<NamedOption[]>('/admin/user-groups/available-model-groups'),
  createUserGroup: (body: any) => api.post<UserGroup>('/admin/user-groups', body),
  updateUserGroup: (id: number, body: any) => api.put<UserGroup>(`/admin/user-groups/${id}`, body),
  deleteUserGroup: (id: number) => api.delete(`/admin/user-groups/${id}`),
  users: () => api.get<Account[]>('/admin/users'),
  createUser: (body: { username: string; password: string; confirmPassword: string; remark?: string; status?: number }) => api.post<Account>('/admin/users', body),
  updateUser: (id: number, body: { password?: string; confirmPassword?: string; remark?: string; status?: number }) => api.put<Account>(`/admin/users/${id}`, body),
  deleteUser: (id: number) => api.delete(`/admin/users/${id}`),
  logs: (params: { page: number; size: number }) => api.get<{ records: AuditLog[]; total: number }>('/admin/logs', { params }),
  logDetail: (id: number) => api.get<AuditLog>(`/admin/logs/${id}`),
}
export const portalApi = {
  models: () => api.get<ModelCatalog>('/console/models'),
  keys: () => api.get<ApiKey[]>('/console/api-keys'),
  createKey: (body: { remark?: string }) => api.post<{ id: number; token: string; remark?: string }>('/console/api-keys', body),
  deleteKey: (id: number) => api.delete(`/console/api-keys/${id}`),
  updateKeyStatus: (id: number, status: number) => api.patch(`/console/api-keys/${id}/status`, { status }),
  usage: (params: { apiKey?: string; model?: string }) => api.get<Usage>('/console/usage', { params }),
  usageRanking: () => api.get<UsageRanking>('/console/usage-ranking'),
  tokenActivity: (params: { apiKey?: string; model?: string; endDate?: string }) => api.get<TokenActivity>('/console/token-activity', { params }),
  changePassword: (body: { currentPassword: string; newPassword: string; confirmPassword: string }) => api.post('/console/change-password', body),
  githubStatus: () => api.get<GithubBinding>('/console/github/status'),
  githubAuthorize: () => api.get<{ authorizationUrl: string }>('/console/github/authorize'),
  unbindGithub: () => api.delete('/console/github/binding'),
}
