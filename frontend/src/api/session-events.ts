type Change = 'changed' | 'expired'
const listeners = new Set<(change: Change) => void>()
const accessListeners = new Set<(organizationId: string) => void>()
let channel: BroadcastChannel | undefined

function sessionChannel() {
  if (!channel && typeof BroadcastChannel !== 'undefined') {
    channel = new BroadcastChannel('stockrescue-session')
    channel.onmessage = event => {
      if (event.data === 'changed') {
        clearLegacyTransferIntents()
        listeners.forEach(listener => { listener('changed') })
      }
    }
  }
  return channel
}
export function clearLegacyTransferIntents() {
  try {
    for (let index = window.sessionStorage.length - 1; index >= 0; index--) {
      const key = window.sessionStorage.key(index)
      if (key?.startsWith('stockrescue.transfer-command.')) window.sessionStorage.removeItem(key)
    }
  } catch { /* Legacy browser drafts are never read by the server-backed recovery flow. */ }
}
export function announceSessionChange() { clearLegacyTransferIntents(); channel?.postMessage('changed') }
export function sessionExpired() { clearLegacyTransferIntents(); listeners.forEach(listener => { listener('expired') }) }
export function accessChanged(organizationId: string) { accessListeners.forEach(listener => { listener(organizationId) }) }
export function subscribeAccessChanges(listener: (organizationId: string) => void) { accessListeners.add(listener); return () => { accessListeners.delete(listener) } }
export function subscribeSessionChanges(listener: (change: Change) => void) {
  listeners.add(listener)
  sessionChannel()
  return () => { listeners.delete(listener); if (!listeners.size) { channel?.close(); channel = undefined } }
}
