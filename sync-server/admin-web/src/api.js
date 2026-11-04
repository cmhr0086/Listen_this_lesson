// The console keeps only a device token, from the same bind-a-device flow phones use. Removing the
// device in the mini program or here signs this browser out at its next request.
const KEY = 'listen-admin-device'
const listeners = new Set()

export const deviceToken = () => {
  try { return localStorage.getItem(KEY) || '' } catch { return '' }
}
export const setDeviceToken = (token) => {
  try { localStorage.setItem(KEY, token) } catch { /* private window: signed in until reload */ }
}
function clear() {
  try { localStorage.removeItem(KEY) } catch { /* nothing stored */ }
  listeners.forEach((fn) => fn())
}
export const onSignedOut = (fn) => { listeners.add(fn); return () => listeners.delete(fn) }

async function request(url, method, body, contentType, auth) {
  const isFile = contentType === 'application/octet-stream'
  const response = await fetch(url, {
    method,
    headers: { ...(auth ? { Authorization: `Bearer ${deviceToken()}` } : {}), ...(body !== undefined ? { 'Content-Type': contentType || 'application/json' } : {}) },
    body: body === undefined ? undefined : isFile ? body : JSON.stringify(body)
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) {
    const error = new Error(typeof payload.detail === 'string' ? payload.detail : `请求失败（${response.status}）`)
    error.status = response.status
    throw error
  }
  return payload
}

export async function api(method, path, body, contentType) {
  try {
    return await request(`/api/admin/v1${path}`, method, body, contentType, true)
  } catch (error) {
    if (error.status === 401) clear()
    throw error
  }
}

export const createPairing = (name) => request('/api/public/v1/devices/pairings', 'POST', { name })
export const claimPairing = (claim) => request('/api/public/v1/devices/pairings/claim', 'POST', { claim })

// Best effort on the server's side: this browser forgets its token either way.
export async function signOut() {
  await request('/api/public/v1/devices/current', 'DELETE', undefined, undefined, true).catch(() => {})
  clear()
}
