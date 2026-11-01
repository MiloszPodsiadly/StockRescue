type Change = 'changed' | 'expired'
const listeners = new Set<(change: Change) => void>()
const accessListeners = new Set<(organizationId: string) => void>()
let channel: BroadcastChannel | undefined

function sessionChannel() {
  if (!channel && typeof BroadcastChannel !== 'undefined') {
    channel = new BroadcastChannel('stockrescue-session')
    channel.onmessage = event => { if (event.data === 'changed') listeners.forEach(listener => { listener('changed') }) }
  }
  return channel
}
export function announceSessionChange() { channel?.postMessage('changed') }
export function sessionExpired() { listeners.forEach(listener => { listener('expired') }) }
export function accessChanged(organizationId: string) { accessListeners.forEach(listener => { listener(organizationId) }) }
export function subscribeAccessChanges(listener: (organizationId: string) => void) { accessListeners.add(listener); return () => { accessListeners.delete(listener) } }
export function subscribeSessionChanges(listener: (change: Change) => void) {
  listeners.add(listener)
  sessionChannel()
  return () => { listeners.delete(listener); if (!listeners.size) { channel?.close(); channel = undefined } }
}
